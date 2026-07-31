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
    """
    One setting, or a message that says what to do about it.

    **A key present with no value counts as set-and-empty**, which is the whole
    point of a config full of optional entities: `weight_entity:` on its own
    means "I do not have a scale", not "you did not answer".

    The horizontal-whitespace class matters and is not fussiness. `\s*` also
    matches newlines, so `weight_entity:` followed by a comment line silently
    returned the first word of that comment — a `#`. Every blank setting in the
    shipped example became a stray character, and nothing said so.
    """
    for path in CANDIDATES:
        if not os.path.exists(path):
            continue
        with open(path) as fh:
            m = re.search(rf"^{key}:[ \t]*(.*)$", fh.read(), re.M)
        if m:
            value = m.group(1).strip()
            # An inline `# comment` after a value is not part of the value.
            value = re.split(r"\s+#", value, 1)[0].strip()
            if value:
                return value
            if default is not None:
                return default
            # Present but empty, and no default: the caller wants a real answer.
            break
    if default is not None:
        return default
    sys.exit(_MISSING.format(
        key=key, here=HERE,
        looked="\n".join("    " + os.path.normpath(p) for p in CANDIDATES)))


def token() -> str:
    return conf("ha_api_token")


HA = conf("ha_url", "http://homeassistant.local:8123").rstrip("/")
WS = HA.replace("https://", "wss://").replace("http://", "ws://") + "/api/websocket"


# --- entity ids that differ per install -------------------------------------
#
# None of these can be guessed. The treadmill's own entities come from MQTT
# discovery and follow the device name, so they *are* predictable — but only
# until somebody renames the device, which Home Assistant lets them do from the
# UI. Everything else here is either created by the user or created by another
# integration entirely.
#
# The defaults are what a clean STRIDE install produces. Anything that does not
# resolve is treated as absent rather than fatal: the coach already knows how
# to say nothing about a metric it was not given.

def treadmill(name: str) -> str:
    """`sensor.treadmill_distance`, unless the device was renamed."""
    return f"sensor.{conf('treadmill_prefix', 'treadmill')}_{name}"


def optional(key: str) -> str:
    """An entity id that may legitimately not exist. Empty means absent."""
    return conf(key, "")


def ai_task() -> str:
    """
    The AI task entity the coach asks for words.

    There is no sensible default. It depends on which conversation integration
    somebody set up — `ai_task.claude_ai_task`, `ai_task.google_ai_task`,
    something local — and getting it wrong fails at call time with an error
    that does not say what is wrong.
    """
    return conf("coach_ai_task")


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
