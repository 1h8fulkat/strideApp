# Security

STRIDE drives a motorised treadmill. On this project, a security problem and a
safety problem are often the same problem — anything that lets someone or
something move the belt when the person standing on it did not ask for it.
Please read [SAFETY.md](SAFETY.md) alongside this.

## Reporting

Open a
[security advisory](https://github.com/keranm/strideApp/security/advisories/new)
rather than a public issue, so it can be looked at before it is widely known.

Please include what you were running, what you did, and what happened. If it
involves the belt moving, say so first — that gets read first.

This is one person's project, not a company with a response team. You will get
an acknowledgement, and an honest answer about whether and when it can be
fixed.

## Supported versions

The tip of `main`. There are no maintained release branches, no backports, and
no security updates for older builds: you update by rebuilding from source and
reinstalling. If you are running something you built months ago, you are
running something nobody is looking after.

## What this software assumes

These are design decisions, not oversights. Knowing them is most of the
security model.

**Your network is the boundary.** MQTT to Home Assistant is plain TCP with a
username and password. The coach's audio is fetched over plain HTTP, because
the console's clock reads 2022 and TLS validation fails against a correct
certificate. Nothing here is safe to expose to the internet, and nothing here
tries to be. Keep the console and the broker on a network you trust.

**ADB is a key to the treadmill.** Installing STRIDE means enabling ADB, and
anyone who can reach it can start the belt. It is authenticated — the console
only accepts host keys it has been shown — so keep that machine's ADB key as
you would an SSH key, and do not authorise a host you do not control.

**The console holds credentials in the clear.** The broker password lives in
the app's private storage, readable by root and by anything with the app's
UID. A release build (`./run.sh release`) is not `debuggable`, which is what
keeps other software on the console out of it; a debug build has no such
protection and should stay on the bench. The settings screen is never sent the
stored password, only whether one exists.

**The physical safety key outranks everything.** No amount of software failure
should be able to keep the belt running when that key is pulled. If you find a
way it can, that is the most serious report this project can receive.

## Out of scope

- Anything requiring physical access to an unlocked console. Whoever is
  standing at the treadmill can already press the buttons.
- The treadmill's own firmware and its stock software. Vulnerabilities there
  belong to their vendor, though we would like to hear about them.
- Home Assistant, Mosquitto, or any other component you point this at.
