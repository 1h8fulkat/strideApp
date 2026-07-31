#!/usr/bin/env python3
"""
One place that knows where Home Assistant is and how to get into it.

Every script here used to carry its own copy of this: a hardcoded
`HA = "http://<an ip>:8123"`, its own `ENV` path two directories up, and
its own `conf()` and `token()`. That was fine for one house and is wrong for
anyone else's — the address of a Home Assistant is not a constant.

Config is read from `stride.conf` beside these scripts, which git ignores. See
`stride.conf.example` for what goes in it.

The `../../env.txt` fallback is the layout this project grew up in. It is kept
so an existing install keeps working after the move, and it is the only reason
this looks in two places.
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

CANDIDATES = [
    os.path.join(HERE, "stride.conf"),
    os.path.join(HERE, "..", "..", "env.txt"),
    os.path.join(HERE, "..", "..", "..", "env.txt"),
]

_MISSING = """
No STRIDE configuration found, and `{key}` is needed.

    cp {here}/stride.conf.example {here}/stride.conf
    then edit it

Looked in:
{looked}
"""


def conf(key: str, default=None) -> str:
    """One setting, or a message that says what to do about it."""
    for path in CANDIDATES:
        if not os.path.exists(path):
            continue
        with open(path) as fh:
            m = re.search(rf"^{key}:\s*(\S+)", fh.read(), re.M)
        if m:
            return m.group(1)
    if default is not None:
        return default
    sys.exit(_MISSING.format(
        key=key, here=HERE,
        looked="\n".join("    " + os.path.normpath(p) for p in CANDIDATES)))


def token() -> str:
    return conf("ha_api_token")


HA = conf("ha_url", "http://homeassistant.local:8123").rstrip("/")
WS = HA.replace("https://", "wss://").replace("http://", "ws://") + "/api/websocket"


def broker() -> str:
    """Host only. The scripts shell out to mosquitto_pub, which wants a host."""
    return conf("mqtt_broker").replace("tcp://", "").replace("ssl://", "").split(":")[0]


def mqtt(topic: str, payload: str, retain: bool = True):
    cmd = ["mosquitto_pub", "-h", broker(),
           "-u", conf("mqtt_user"), "-P", conf("mqtt_pass"),
           "-t", topic, "-m", payload]
    if retain:
        cmd.insert(1, "-r")
    subprocess.run(cmd, check=True)
