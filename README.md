# 🎙️🗣️📤 Voice Send

A plugin for Nagram XF. While you dictate with Gboard voice typing, say "send" and the message goes out, exactly like tapping the send button. Say "paste" and whatever you copied goes into the message box. Say "screenshot" or "picture" and your newest screenshot or camera photo is attached to the message you are writing. Hold the paperclip and screenshots keep coming for as long as you hold.

## Why I made it

Gboard's voice typing understands "send" in Telegram, WhatsApp and a few other apps. In Nagram XF it answers "Command not available in this app" or types the word, so every dictated message still needs a tap. I wanted to finish a message by voice.

## What it changes

* Saying "send" while dictating presses the send button for you: replies, topics, captions, silent send and scheduled messages behave as a tap would.
* It fires on the first try, even when you say "send" straight after the sentence with no pause, or right after a spoken punctuation command ("close parenthesis", "period", "question mark" and so on). In those cases Gboard on its own types the word instead; the plugin removes it and sends. The word never lands in the message.
* What counts as the command: "send" as the last word of what you just dictated, whatever word comes before it, in any case, with or without a period after it. "That looks good send" goes out as "that looks good".
* What stays text: typed or swiped "send", "send" in the middle ("send it to Tim") and longer words ("resend").
* Saying "paste" as the last word puts your clipboard text in the message box in place of the word, without sending. Gboard sometimes writes it "past"; both count. "Paste send" pastes, then sends.
* "Paste" stays text after "copy and", "cut and", "copy", "the", "half", "quarter", "went", "go", "goes", "going", "walked", "drove", "ran", "get", "got", "way", "long" and "far", so "copy and paste", "in the past" and "half past" are typed as words. With an empty clipboard the word stays as text too.
* The clipboard is read only at the moment you say "paste", and its text is never logged. A second switch turns paste off.
* Saying "screenshot" as the last word attaches your newest screenshot to the message you are writing. It waits in a small strip above the message box and nothing is sent until you send. "Screen shot" and "screenshots" count too.
* Say "screenshot" again and the one before it is added, then the one before that, up to ten, never the same file twice. They go out together as one album, in the order you added them.
* Whatever is in the message box goes out as the caption. "Screenshot send" attaches, then sends.
* With screenshots waiting, the send button shows even when the box is empty, so a tap sends them too.
* Saying "picture" does the same with the newest photo of your camera roll (DCIM/Camera, photos only, no videos). Say it again for the photo before it. "Picture send" attaches, then sends.
* "Screenshot" and "picture" share one strip, so "screenshot", "picture", "picture" goes out as one album in the order you said them.
* A long press on the paperclip attaches your newest screenshot without a word. Keep holding and the one before it is added about every 0.7 seconds, with a light tick each (none when vibration is off in the app), until you lift your finger. A short tap opens the attach menu as before. Stock XF does nothing on a long press of the paperclip, so nothing is lost.
* The X on the strip removes them. The strip also empties when the message is sent, when you empty the message box by hand and when you leave the chat. After that, "screenshot" and "picture" start again from the newest.
* "Screenshot" and "picture" stay text after "a", "the", "this", "that", "my", "your", "of", "in", "take", "send", "see", "big", "profile", "nice" and similar words, so "take a screenshot", "send me a picture", "in the picture" and "look at this screenshot" are typed as words. They also stay text in the middle of a sentence ("take a picture of it") and while you edit a message. Said on its own after a pause, the word is the command.
* The lookup reads only the newest rows of your Screenshots folder (Pictures/Screenshots or DCIM/Screenshots) or of DCIM/Camera, and runs on a background thread. Screenshot, picture and the paperclip hold each have their own switch.
* A screenshot or photo you took a second ago is the one you get. Android takes a moment to finish saving a new screenshot (on a Pixel 6 Pro up to about a second, longer for a big one) and hides it while it does, so 1.4.0 could attach the one before it. Since 1.4.1 the lookup sees a picture that is still being saved, waits on its background thread until it is finished (checking every 0.1 second, at most 8 seconds) and attaches it. With nothing being saved there is no wait and no extra work.
* While you edit a message, "send" saves the edit, like the check mark.
* In slow mode it does what tapping the timer does.
* The Enter key still makes a new line, your Send by Enter setting stays as it is, and autocorrect and suggestions are unchanged.
* Work happens once when a chat's message field is created, a short text check once per finished word, and once for each spoken "send". Nothing runs per letter or per frame.

Settings: one switch each for send, paste, screenshot, picture and the paperclip hold. All are on at first.

## Install

1. Use the plugin build of Nagram XF and turn on the plugin engine (Settings, N Settings, Plugins).
2. Download `voice_send.plugin` from the latest release.
3. Send it to your Saved Messages, tap it and choose install. You can also use the plus button on the Plugins screen.
4. Make sure Voice Send is switched on.

## How it works

Gboard runs its voice "send" command only in apps on its own list (the official Telegram package is on it, XF's package is not) or in a field whose EditorInfo says it takes a send action: the IME action SEND, or the private IME option `handlesSendAction`. The plugin hooks `ChatActivityEnterView.createMessageEditText` and adds only `handlesSendAction` to the message field's private IME options, so `imeOptions` and the Enter key stay as they were. Gboard then calls `performEditorAction(IME_ACTION_SEND)`; a hook on `TextView.onEditorAction` answers it for that field only by pressing the send button (the done button while editing, the slow mode button while slow mode counts down).

Gboard does not always treat "send" as a command. Its modeless command detector (a MobileBERT intent classifier with a 0.95 threshold that also reads the text before the cursor and spoken punctuation) often scores "send" as dictation when it follows the sentence with no pause or comes right after a spoken punctuation command, and then simply types it as the last word. So the plugin also watches the message field's own InputConnection (`commitText` and `finishComposingText`, which arrive once per finished word or phrase, never per letter) for a finished chunk whose last word is "send". When that happens while voice typing is on, the word is dropped and the send button is pressed. When the field was empty a moment before (the whole message was one dictated chunk), XF is still fading the send button in, so the plugin calls the same `sendMessage` method the button calls. Voice typing counts as on while any app has the microphone open or closed it less than 3 seconds ago; a tiny Java class bundled in the plugin (`java/`, built into the plugin file by `build.py`) listens to Android's `AudioRecordingCallback`, which fires only when a recording starts or stops. Typed or swiped text never has the microphone on, so it is never taken as the command. Errors, and one line per caught "send", go to logcat under the tag `VoiceSend`.

Stock XF has no place where a photo can wait in the message box. Its attach sheet is a separate window, so opening it takes the keyboard away and ends the dictation, and it forgets its ticked photos every time it opens. So spoken "screenshot" and "picture" keep their own small tray per message box (`ShotTray`, the second Java class in the plugin). One MediaStore query for the newest rows of the Screenshots or Camera folder and the thumbnail run on a background thread; the UI thread only adds the thumbnail to the strip. Every way of sending the box (the send button, Enter, spoken "send", silent and scheduled send) ends in `ChatActivityEnterView.sendMessageInternal`; a hook there sees a tray that is not empty and hands the files to `ChatActivity.didSelectSearchPhotos`, the app's own call for picked photos, with the box text as the caption of the first one. Replies, topics and slow mode behave as they do for photos picked in the attach sheet. Text too long for a caption follows the album as its own message. While the tray is empty none of its listeners exist.

The paperclip hold (`ClipHold`, the third Java class) uses the button's own long press detection, then one posted repeat every 0.7 seconds while the finger is down, removed the moment it lifts. The button's click listener is untouched.

## Build

`voice_send.plugin` is `voice_send.src.py` plus the compiled Java classes (MicWatch, ShotTray and ClipHold). `build.py` lists the javac and d8 steps and writes the plugin file.

## Tested

Nagram XF 12.10.1 plugin build on a Pixel 6 Pro with Gboard 18.5:

* The message field's EditorInfo carries `handlesSendAction`, the action and the no enter action flag are unchanged.
* `performEditorAction(IME_ACTION_SEND)` on the field's own InputConnection sent the text and cleared the field.
* In edit mode the same action saved the edit.
* With Gboard voice typing listening, the field received "VS voice test", then ")", then " send" as separate commits: the message went out without the word "send".
* The same chunk finished through `setComposingText` plus `finishComposingText` also sent.
* With the microphone off, ") send" and "send" stay as typed text, and "please send" never triggers.
* The extra hook costs about 0.15 ms per finished word.

Version 1.4.1, Pixel 6 Pro test phone, real screenshots taken with the screenshot key, in Saved Messages:

* Before the fix (1.4.0): a screenshot, then a paperclip hold 0.2 to 0.4 seconds later, attached the screenshot before it in 6 of 6 tries. The new screenshot was still being saved at that moment (its file was named .pending and its MediaStore row hidden).
* 1.4.1, keyboard open: a screenshot, then a paperclip hold after a gap of 0.1 to 2 seconds, attached that screenshot in 23 of 23 holds. When it was still being saved the plugin waited 108 to 323 ms for it. Two more holds at 0.1 and 0.15 seconds never became a long press at all: the screenshot overlay appearing cancels a finger that is already down, the same on 1.4.0.
* 1.4.1, keyboard closed: the paperclip's long press right after a screenshot attached that screenshot 3 of 3 times (waited 764 to 862 ms).
* Spoken "screenshot" right after a screenshot attached that screenshot 3 of 3 times (waited 315 to 645 ms).
* "Picture" while a camera photo was still being written (a real pending MediaStore row, written over 1.5 seconds) attached that photo after 1.2 seconds.
* A photo still being written after 8 seconds: the plugin gave up and attached the one before, as before. One deleted while being written: the one before. One left unfinished for over a minute: skipped at once, no wait.
* Leaving the chat while a lookup was waiting stopped the wait at once and attached nothing.
* Holding longer right after a screenshot added the new screenshot first, then the older ones in order. Six of them went out as one album; the X, leaving the chat, landscape and back, and ten minutes of normal use in the chat all behaved as in 1.4.0, with an empty crash log and no ANR.
* A normal pick with nothing being saved: 37.7 ms on average over 50 picks for 1.4.1, 37.1 ms over 35 picks for 1.4.0, on the background thread. Nothing runs on the main thread that did not before.

Version 1.4.0, same phone, Gboard voice typing listening, chunks replayed into the field's InputConnection, real screenshots and camera photos on the phone, in Saved Messages:

* "Screenshot" into an empty box: the word was dropped, the newest screenshot showed in the strip, the send button appeared, nothing was sent.
* "VS three in a row." then "Screenshot", "screenshot", "Screenshots." as separate chunks: three different screenshots in the strip, newest first. " send" sent them as one album with that caption.
* "Screenshot send" as one chunk into an empty box sent the newest screenshot.
* Text, then "screenshot", then a tap on the send button: one photo with the text as its caption.
* "Picture" attached the newest camera photo; a tap on send sent it with its caption. "Picture send" as one chunk sent it.
* "Screenshot", "picture", "Picture." and "screenshot picture" made one strip of five in spoken order, sent by " send" as one album of five.
* "take a screenshot", "of the page. The screenshot shows", "Send me a screenshot", "look at this screenshot.", "my screenshots", "take a picture", "of it. In the picture", "is my profile picture" and "the big picture." all stayed text with the microphone on.
* With the microphone off for more than 3 seconds, "Screenshot" stayed text.
* With the screenshot and picture switches off, "Picture" and "Screenshot" stayed text.
* The X emptied the strip. Emptying the box by hand emptied it. Leaving the chat and coming back emptied it.
* Paperclip, real touches: a 300 ms press opened the attach menu and added nothing; a 700 ms hold added one screenshot and opened no menu; a 3 second hold added four more in order; a tap on send sent all five as one album. With its switch off, a hold only opened the menu.
* The per word cost is unchanged from 1.3.0 (0.8 ms for 1.3.0 and for 1.4.0 in the same 300 commit run, the app's own work included).

Not covered by a test: the strip with the reply bar open, slow mode, scheduled sending, a caption longer than the limit, and more than ten attachments.

Version 1.3.0, same phone, same method, with a test text on the 6 Pro's clipboard:

* "VS p1 here it is paste" became "VS p1 here it is" plus the clipboard text, not sent.
* "VS p2 look at this paste send" went out with the clipboard text in place of "paste".
* "VS p3 the link is past" as composing text plus `finishComposingText` pasted.
* "copy and paste", "in the past" and "cut and paste" stayed as text with the microphone on.
* With an empty clipboard, "VS p5 empty paste" stayed as text.
* With the microphone off for more than 3 seconds, "VS p6 typed paste" stayed as text.

Version 1.2.0, same phone, Gboard voice typing listening, chunks replayed into the field's InputConnection:

* "VS one that looks good send" as one chunk into an empty field went out as "VS one that looks good".
* "VS two which file should I send" as composing text plus `finishComposingText` went out without "send".
* "VS five done. Send." went out as "VS five done."
* ")" then " send" as separate chunks went out without "send".
* With the microphone switched off half a second earlier, "VS seven grace send" still went out (the 3 second window).
* With the microphone off for more than 3 seconds, "VS typed send" stayed as text.
* With the microphone on, "VS send it to Tim" and " resend" stayed as text.
* About 0.2 to 0.3 ms per finished word with the plugin loaded.

## License

MIT

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=firestarter1996/xf-voice-send&type=Date)](https://star-history.com/#firestarter1996/xf-voice-send&Date)
