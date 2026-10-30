#!/usr/bin/env python3
"""Open an Ember history archive (ember-archive-*.zip) on a computer.

    python open_archive.py ember-archive-20261026-164045.zip OUTPUT_FOLDER [backup key or passphrase]

Writes chats.json (every chat with its messages) and the files from media/ into OUTPUT_FOLDER. Archives made by Ember 0.4 and later are sealed with
AES-256-GCM under your account's backup key (Settings, Backup and encryption, Backup key in Ember): give that key as the last argument. Older ones
used a passphrase: PBKDF2-SHA256 over it (rounds and salt are in archive.json). Every file inside is its 12 byte nonce followed by the sealed data.
Needs the `cryptography` package for a sealed archive (pip install cryptography). The Ember Backup Reader (tools/backup-reader) does the same
with windows and can sign in to your server for the key.
"""
import base64
import getpass
import hashlib
import json
import os
import sys
import zipfile


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    archive, out = sys.argv[1], sys.argv[2]
    z = zipfile.ZipFile(archive)
    header = json.loads(z.read("archive.json"))
    opener = lambda b: b
    if header.get("encrypted"):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM

        if header.get("keyed"):
            text = (sys.argv[3] if len(sys.argv) > 3 else getpass.getpass("Backup key: ")).strip().replace(" ", "")
            key = base64.b64decode(text + "=" * (-len(text) % 4))
            if len(key) != 32:
                sys.exit("That is not a backup key (it is 43 letters and numbers).")
        else:
            passphrase = sys.argv[3] if len(sys.argv) > 3 else getpass.getpass("Passphrase: ")
            key = hashlib.pbkdf2_hmac("sha256", passphrase.encode(), base64.b64decode(header["salt"]), header["rounds"], 32)
        aes = AESGCM(key)
        opener = lambda b: aes.decrypt(b[:12], b[12:], None)
    os.makedirs(os.path.join(out, "media"), exist_ok=True)
    for name in z.namelist():
        if name == "chats.json" or name.startswith("media/"):
            try:
                data = opener(z.read(name))
            except Exception:
                sys.exit("Wrong key or passphrase, or the file is damaged.")
            with open(os.path.join(out, name), "wb") as f:
                f.write(data)
    index = json.load(open(os.path.join(out, "chats.json"), encoding="utf8"))
    chats = index["chats"]
    print(f"{len(chats)} chats, {sum(len(c['messages']) for c in chats)} messages, {len(os.listdir(os.path.join(out, 'media')))} files")
    for s in index.get("skipped", []):
        print("skipped:", s)


if __name__ == "__main__":
    main()
