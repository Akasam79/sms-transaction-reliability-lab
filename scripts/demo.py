"""Local demonstration only: invented accounts, simulated NGN balances and SMS gateway."""
import hashlib
import hmac
import json
import os
import time
import urllib.request
import uuid
from datetime import datetime, timezone

BASE = os.getenv("KEENPAY_BASE_URL", "http://localhost:8080")
OPERATOR = os.environ["KEENPAY_OPERATOR_KEY"]
GATEWAY = os.environ["KEENPAY_GATEWAY_SECRET"]


def post(path, payload, headers):
    raw = json.dumps(payload, separators=(",", ":")).encode()
    request = urllib.request.Request(BASE + path, raw, headers={"Content-Type": "application/json", **headers})
    with urllib.request.urlopen(request) as response:
        return json.load(response)


def sms(phone, command):
    payload = {"phone": phone, "sms": command}
    raw = json.dumps(payload, separators=(",", ":")).encode()
    timestamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    message_id = str(uuid.uuid4())
    signature = hmac.new(GATEWAY.encode(), timestamp.encode() + b"." + message_id.encode() + b"." + raw, hashlib.sha256).hexdigest()
    return post("/api/v1/sms/inbound", payload, {"X-KeenPay-Timestamp": timestamp,
        "X-KeenPay-Message-Id": message_id, "X-KeenPay-Signature": signature})["reply"]


suffix = str(int(time.time()))[-8:]
customer = "+23480" + suffix
merchant = "+23481" + suffix
headers = {"X-KeenPay-Operator-Key": OPERATOR}
post("/api/v1/simulation/accounts", {"phone": customer, "role": "CUSTOMER", "name": "Ada Example", "pin": "123456"}, headers)
shop = post("/api/v1/simulation/accounts", {"phone": merchant, "role": "MERCHANT", "name": "Demo Shop", "pin": "654321"}, headers)
post("/api/v1/simulation/fund", {"phone": customer, "amountNaira": 100}, headers)
print(sms(customer, "BALANCE 123456"))
reply = sms(customer, "PAY " + shop["merchantCode"] + " 40")
print(reply)
reference = reply.split("CONFIRM ", 1)[1].split(" ", 1)[0]
print(sms(customer, "CONFIRM " + reference + " 123456"))
print(sms(customer, "BALANCE 123456"))
print(sms(merchant, "BALANCE 654321"))
