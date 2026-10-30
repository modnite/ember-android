# Backups

Settings, Backup and encryption.

## What a backup contains

Each run writes two files to every place you added:

| File | Contents |
|---|---|
| `ember-backup-<date>.embkup` | Your encryption key, settings and stickers |
| `ember-archive-<date>.zip` | A readable archive of your chats and their files. Included unless you switch it off under Included |

The newest five runs are kept per place.

## Places

- Your Hearth server (kept per person, with a quota the server owner sets)
- A folder Android can reach, which includes Google Drive, OneDrive, Proton Drive and Dropbox through their apps
- WebDAV or Nextcloud
- S3-compatible storage such as MinIO, Backblaze B2 or Wasabi (SigV4)
- FTP or FTPS
- SFTP

Any number of places can be active at once.

## Schedule

Daily, weekly or monthly, at a time of day you pick. A run that could not start on time (phone off, no Wi-Fi) starts when Ember is opened or the phone allows it. Back up now makes a complete backup at any time. A running backup or restore shows a strip on every screen, including the sign-in screen, and only Cancel stops it.

## Sealing and keys

- Every file is sealed with AES-256-GCM.
- The key belongs to your account. The Hearth server makes it the first time and hands it only to you after sign-in (`GET /api/backup/key`).
- From 0.6 the key is also stored inside each backup, wrapped with your account password (Argon2id). A backup then opens with the password alone, in Ember or in the Backup Reader.
- The key is shown under Backup key, for reading a backup on a computer.
- Backups made before 0.4 were sealed with a passphrase and still open.

## Restoring

- A new phone: the first sign-in to an existing account offers the newest backup from your Hearth server, from another place, or from a file. It uses the password you just typed.
- Later: Settings, Backup and encryption, Restore. Restores keep running when you leave the page.

## Reading a backup on a computer

- [Backup Reader](../tools/backup-reader): Windows, macOS and Linux. It asks your server for your key after you sign in, or takes the key from Ember. It lists chats, searches, shows pictures and saves files. It cannot open anyone else's backup.
- `tools/open_archive.py` does the same on the command line.
- Release assets named `Ember-Backup-Reader-<OS>.zip` come from the `backup-reader` workflow.
