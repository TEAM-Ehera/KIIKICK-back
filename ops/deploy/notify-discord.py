"""Send one Jenkins deployment event using the shared Discord transport."""

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "observability"))
from discord_notify import post_discord

MESSAGES = {
    "started": "🚀 KIIKICK 서버 재배포 중",
    "succeeded": "✅ KIIKICK 서버 배포 완료",
    "failed": "❌ KIIKICK 서버 배포 실패",
}


if __name__ == "__main__":
    try:
        message = MESSAGES[os.environ["KIIKICK_DEPLOY_STATE"]]
        job = os.environ["JOB_NAME"]
        build = os.environ["BUILD_NUMBER"]
        post_discord(f"{message}\n{job} #{build}")
    except Exception:
        sys.exit("Discord deployment notification could not be sent.")
