"""Forward CloudWatch alarm transitions and daily traffic summaries to Discord."""

import json
import os
import time
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen

KST = timezone(timedelta(hours=9))


def post_discord(message):
    webhook = urlsplit(os.environ["DISCORD_WEBHOOK_URL"])
    if (
        webhook.scheme != "https"
        or webhook.netloc != "discord.com"
        or not webhook.path.startswith("/api/webhooks/")
        or webhook.query
        or webhook.fragment
    ):
        raise ValueError("Invalid Discord webhook configuration")
    body = json.dumps(
        {"content": message, "allowed_mentions": {"parse": []}}
    ).encode()
    request = Request(
        webhook.geturl() + "?wait=true",
        data=body,
        headers={"Content-Type": "application/json", "User-Agent": "KIIKICK-Ops/1.0"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=8):
            pass
    except HTTPError as error:
        raise RuntimeError(f"Discord returned HTTP {error.code}") from None
    except (URLError, TimeoutError):
        raise RuntimeError("Discord webhook request failed") from None


def daily_report(logs, end):
    start = end - timedelta(days=1)
    query = logs.start_query(
        logGroupName=os.environ["NGINX_LOG_GROUP"],
        startTime=int(start.timestamp()),
        endTime=int(end.timestamp()),
        queryString=(
            "filter ispresent(status) | stats count(*) as requests by status "
            "| sort requests desc | limit 600"
        ),
    )
    for _ in range(25):
        result = logs.get_query_results(queryId=query["queryId"])
        if result["status"] == "Complete":
            break
        if result["status"] not in {"Scheduled", "Running"}:
            raise RuntimeError(f"Logs Insights query ended with {result['status']}")
        time.sleep(2)
    else:
        logs.stop_query(queryId=query["queryId"])
        raise TimeoutError("Logs Insights query did not complete in time")

    counts = {}
    for row in result["results"]:
        fields = {item["field"]: item["value"] for item in row}
        counts[int(fields["status"])] = int(fields["requests"])
    total = sum(counts.values())
    errors = [(status, count) for status, count in counts.items() if 500 <= status < 600]
    error_count = sum(count for _, count in errors)
    period = f"{start:%m-%d %H:%M}–{end:%m-%d %H:%M} KST"
    if not total:
        return f"**KIIKICK 운영 리포트** ({period})\n수집된 요청 없음. 트래픽과 로그 수집 상태를 확인하세요."
    top = ", ".join(f"HTTP {status} {count}건" for status, count in errors[:3]) or "없음"
    return (
        f"**KIIKICK 운영 리포트** ({period})\n"
        f"요청 {total:,}건 · 5xx {error_count:,}건 ({error_count / total:.2%})\n"
        f"상위 5xx 상태 코드 {top}"
    )


def alarm_message(event):
    data = event["alarmData"]
    state = data["state"]["value"]
    if state != "ALARM" and not (state == "OK" and data["previousState"]["value"] == "ALARM"):
        return None
    name = data["alarmName"]
    region = event["region"]
    params = urlencode({"region": region})
    alarm_id = quote(name, safe="")
    link = f"https://console.aws.amazon.com/cloudwatch/home?{params}#alarmsV2:alarm/{alarm_id}"
    timestamp = datetime.fromisoformat(event["time"].replace("Z", "+00:00")).astimezone(KST)
    status = "장애" if state == "ALARM" else "복구"
    return f"**KIIKICK 운영 {status}** · {timestamp:%Y-%m-%d %H:%M:%S} KST\n[{name}]({link})"


def lambda_handler(event, context):
    if event.get("type") == "daily-report":
        import boto3

        message = daily_report(boto3.client("logs"), datetime.now(KST))
    else:
        message = alarm_message(event)
    if message:
        post_discord(message)
