# Changelog

One entry per release. All dates are 2026.

## 1.4.2 (1 Nov)
- The call notification no longer sets its own accent colour. In One UI dark mode the Mute button text took that colour and could not be read; the buttons now use the system's own text colour in light and dark.

## 1.4.1 (1 Nov)
- Group calls: the fit/fill button now follows what the tiles show. Before, with pictures fitting by themselves the first press changed nothing visible, and a quick second press acted on a stale value, so it felt fiddly. It now flips what you see at once, every press.

## 1.4.0 (1 Nov)
- The ongoing call notification is the standard call notification: tap it to return to the call, End call and Mute (it says Unmute while you are muted) on it. From Android 12 it is drawn in the system call style.
- Echo cancelling is on from the start on the Pixel 3 family (Pixel 3, 3 XL, 3a, 3a XL), whose own echo cancelling does badly on speakerphone: the other person on the call heard themselves. Settings, Extra echo cancelling can still switch it either way.
- Calls sideways: the buttons at the right edge are bigger (40dp, up from 38dp) and further apart, so they are not missed. The camera flip button on a big picture sits clear of them.
- Fit and fill: the button on a big picture has a 48dp touch area, acts on what is shown at that moment (so a quick second press is never ignored) and no longer makes every call tile lay itself out again each time the call state changes.

## 1.3.0 (1 Nov)
- Mentions: typing @ in a group lists its members above the message box, narrows as you type, and puts in the name when you pick one. An @ inside an email address does not open the list. A name after @ in a message is highlighted, and yours stands out more. The web app does the same.
- Shared pictures, files and voice: Chat details have a new row that opens everything shared in the chat, newest first. Pictures and videos in a grid by month, files and voice messages in a list, older ones on request.
- The sign-in screen shows the Ember speech bubble and flame on its own in the accent colour, no circle around it.

## 1.2.0 (31 Oct)
- Spacing matches the web app: section headings, settings rows, people rows and the archived row use the same sizes in both.
- This release rolls up the interface round: the new wallpaper model, regrouped settings and the sideways fixes from 1.1.7. From here the last number is for small fixes and the middle number for features, improvements and major fixes.

## 1.1.7 (31 Oct)
- Wallpapers work like the big messengers: a flat colour that follows the theme and accent, a switch for doodles, and a dimming slider. "Accent colour" washes the flat colour. A photo can still be used. Earlier choices carry over.
- The doodles are now 84 large icons and 28 small shapes, placed off any grid, with no icon near a copy of itself. The same picture as the web app.
- Settings are regrouped (Preferences, Account and security, About) with section headings, shorter wording, and two columns sideways. Sounds in chats moved to Notifications and sounds, crash reports to About.
- Sideways, the bottom bar's highlight no longer touches the top edge.

## 1.1.6 (31 Oct)
- The default wallpaper is a dense scatter in the way of the big messengers: 34 larger outline icons (camera, cup, headphones, umbrella, paw, gift and more) with small dots, rings and sparkles filling every gap. The same picture as on the web.

## 1.1.5 (31 Oct)
- The default wallpaper is jumbled and full: shapes sit off any grid, at different sizes, turned every way, with no empty patches. It is the same scatter as on the web.

## 1.1.4 (31 Oct)
- Spelling suggestions stay on screen. The strip above the message box keeps the mistake it is on until you fix or ignore it, instead of vanishing when the cursor moves. It shows the word, how many mistakes there are (tap it for the next one), up to five suggestions, and Ignore for words you want left alone.
- The squiggle under a mistake is an even zigzag, drawn at the foot of the line below the letters.
- Test spell checking has a clearer window: the verdict, the sample with the mistakes marked, each mistake with its suggestions, and which checker answered.

## 1.1.3 (31 Oct)
- The wallpaper shapes are drawn as clean single strokes: the flower, asterisk, bolt, speech bubble and leaf no longer overlap themselves.

## 1.1.2 (31 Oct)
- Chat bubbles have the same shape as in the web app: round all the way, with the corner towards the sender tightened.
- More room between bubbles, a little inside a run and more between runs.
- The doodle wallpaper is bolder and closer together, with sixteen different shapes instead of seven.
- The backup key window is shorter: the key in its own box, what the key ID is for, and one line about keeping it private.

## 1.1.1 (30 Oct)
- Samsung front cameras deliver an already flipped picture. Others saw you mirrored (writing backwards) and your own preview looked wrong. The picture is now flipped once before it is sent, so others see you as you are (writing reads the right way round). Settings, Chats and calls, Flip front camera for others is on by default for Samsung.
- Mirror my own picture (Settings, Chats and calls): on by default, you see yourself like in a mirror. Switch it off to see the picture as others see it.
- The restore offer after the first sign-in has shorter text and its buttons are always visible, also in landscape.

## 1.1.0 (30 Oct)
- Low battery in calls: a warning at 20, 15, 10 and 5 percent (notification and banner, plug in and use a fast charger), and the others in the call are told. Switch under Notifications and sounds.
- Crash reports are sent to your Hearth server at the next start. Switch under Chats and calls.
- Settings, Chats and calls, Test spell checking: shows the spell checker in use and what it marks in a sample.
- Backup Reader packages for Windows, macOS and Linux are attached to the releases again.

## 1.0.0 (30 Oct)
- The update notification switch moved under Notifications and sounds.

## 0.9 (30 Oct)
- 0.9.9: the flame is back inside the chat bubble icon.
- 0.9.8: the message box turns the keyboard's autocorrect off. Spell checking asks word by word when the sentence check returns nothing.
- 0.9.7: the update check runs every hour.
- 0.9.6: spell checking in the message box.
- 0.9.5: a notification when an update is available.
- 0.9.4: add and remove people in groups, rename groups, search boxes keep their text and keyboard.
- 0.9.3: Create an account is always on the sign-in screen.
- 0.9.2: Back up now always makes a complete backup.
- 0.9.1: chat history is included in backups unless switched off.
- 0.9.0: S3, FTP and SFTP places. Restore offer on the first sign-in.

## 0.8 (30 Oct)
- 0.8.3: backup and restore progress is always visible.
- 0.8.2: backups run as a foreground job that only Cancel stops. Restores continue when you leave the page.
- 0.8.1: settings pages scroll above the keyboard. Dialog fields scroll into view.
- 0.8.0: change username and password, friend codes, friend requests with accept, reject and block.

## 0.7 (30 Oct)
- Join without an invite code when the server is open. Optional two-step sign-in.

## 0.6 (30 Oct)
- The backup key is kept under your account password (Argon2id). Backup Reader signs in with the password.

## 0.5 (29 Oct)
- Smaller, faster backups. Plain update progress bar.

## 0.4 (29 Oct)
- Backups sealed with an account key. Time of day for automatic backups. Desktop Backup Reader. Chat wallpapers. New icon.
- 0.4.1 and 0.4.2: message box icon centring, backup time chosen by hour with AM or PM.

## 0.3 (26 to 29 Oct)
- GIF and sticker search, full-screen viewers, keyless sources (Signal packs, anime reactions).
- Calls: screen stays on, picture fit and fill, floating window, software echo cancelling, who is muted, mute for me, double tap to swap pictures.
- Nudges: counted lines, floating button, loud during calls.
- Reactions on stickers, GIFs, pictures, videos and files.
- Call and nudge buttons in the top bar, emoji, GIF and sticker drawer with suggestions, recent emoji tab.

## 0.2 (26 Oct)
- Video playback, in-app camera, stickers and GIFs, group pictures, backups to many places with a schedule and restore.

## 0.1 (26 Oct)
- First release: chats, calls, E2EE, notifications, in-app updates.
- 0.1.1: Telecom call stays registered, nudge and hangup sounds, localhost through VPNs.
- 0.1.2: updates download through GitHub's redirect.
