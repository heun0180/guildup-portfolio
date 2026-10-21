#!/usr/bin/env python3
"""새 로컬 클러스터에서만 PostgreSQL 검증. 기존 DB 접속 정보는 받지 않는다."""
from pathlib import Path
import json
import os
import shutil
import socket
import subprocess
import tempfile
import uuid
import xml.etree.ElementTree as ET


def main():
    base = Path(__file__).resolve().parent.parent
    tools = {name: shutil.which(name) for name in ("initdb", "pg_ctl", "psql")}
    maven = os.environ.get("SETTLEMENT_MAVEN") or shutil.which("mvn")
    if not all(tools.values()) or not maven:
        raise SystemExit("Requires initdb, pg_ctl, psql and Maven; no existing server will be used.")
    if any(key.startswith(("SPRING_DATASOURCE_", "SPRING_CONFIG_")) or key == "SPRING_APPLICATION_JSON" for key in os.environ):
        raise SystemExit("Remove external Spring datasource/config overrides for this isolated test.")
    target = base / "target"
    evidence = target / "verification"
    evidence.mkdir(parents=True, exist_ok=True)
    home = Path(tempfile.mkdtemp(prefix="postgres-", dir=target))
    data = home / "data"
    env = {key: value for key, value in os.environ.items() if not key.startswith("PG")}
    env["PGPASSFILE"] = os.devnull
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    database = "settlement_example_" + uuid.uuid4().hex
    env["SETTLEMENT_TEST_PG_PORT"] = str(port)
    env["SETTLEMENT_TEST_PG_DATABASE"] = database
    started = False

    def run(command):
        result = subprocess.run(command, cwd=base, env=env, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=180)
        if result.returncode:
            raise RuntimeError(result.stdout)
        return result.stdout

    try:
        version = run([tools["initdb"], "--version"]).strip()
        run([tools["initdb"], "-D", str(data), "-U", "example_runner", "-A", "trust", "--no-locale", "--encoding=UTF8"])
        # Unix socket은 만들지 않고 새 임의 포트의 loopback 주소만 사용한다.
        run([tools["pg_ctl"], "-D", str(data), "-l", str(evidence / "postgres-server.log"),
             "-o", f"-h 127.0.0.1 -p {port} -k ''", "-w", "-t", "15", "start"])
        started = True
        run([tools["psql"], "-h", "127.0.0.1", "-p", str(port), "-U", "example_runner", "-d", "postgres",
             "-v", "ON_ERROR_STOP=1", "-c", f'CREATE DATABASE "{database}"'])
        # clean은 실행하지 않는다. 실행 중인 폐기용 클러스터도 target 아래에 있다.
        build = subprocess.run([maven, "-B", "-ntp", "-Ppostgres", "verify"], cwd=base, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=180)
        (evidence / "postgres-build.log").write_text(build.stdout)
        print(build.stdout)
        if build.returncode:
            raise SystemExit(build.returncode)
        report = ET.parse(target / "failsafe-reports/TEST-io.github.heun0180.portfolio.settlement.PostgresClaimIT.xml").getroot()
        counts = {key: int(report.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
        assert counts["tests"] > 0 and counts["failures"] == counts["errors"] == counts["skipped"] == 0
        summary = {"server": version, "database": "new disposable local cluster", **counts}
        (evidence / "postgres-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        print("POSTGRES_RESULT", json.dumps(summary))
    finally:
        # 이 스크립트가 만든 디렉터리만 지정한다. 기존 서버는 조작하지 않는다.
        running = started or (data / "postmaster.pid").exists()
        if running:
            stopped = subprocess.run([tools["pg_ctl"], "-D", str(data), "-m", "fast", "-w", "-t", "15", "stop"],
                                     env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            if stopped.returncode:
                raise RuntimeError("Disposable cluster shutdown failed; retained its directory for inspection.")
        shutil.rmtree(home)
        print("Disposable PostgreSQL stopped and its cluster directory removed.")


if __name__ == "__main__":
    main()
