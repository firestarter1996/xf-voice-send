# 🎙️🗣️📤 Voice Send

A plugin for Nagram XF. While you dictate with Gboard voice typing, say "send" and the message goes out, exactly like tapping the send button. Say "paste" and whatever you copied goes into the message box. Say "screenshot" or "picture" and your newest screenshot or camera photo is attached to the message you are writing. Say "select all" and everything in the message box is selected. Hold the paperclip and your newest picture is attached straight into the message box, one more for as long as you hold, without the attach menu opening. A setting picks what the hold takes: your newest screenshots, or your newest images from any folder.

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
* Saying "screenshot" as the last word attaches your newest screenshot to the message you are writing. It waits in a small strip above the message box, drawn like the app's own photo picker (square pictures with its numbered blue ticks), and nothing is sent until you send. "Screen shot" and "screenshots" count too.
* Say "screenshot" again and the one before it is added, then the one before that, up to ten, never the same file twice. They go out together as one album, in the order you added them.
* Whatever is in the message box goes out as the caption. "Screenshot send" attaches, then sends.
* With screenshots waiting, the send button shows even when the box is empty, so a tap sends them too.
* Saying "picture" does the same with the newest photo of your camera roll (DCIM/Camera, photos only, no videos). Say it again for the photo before it. "Picture send" attaches, then sends.
* "Screenshot" and "picture" share one strip, so "screenshot", "picture", "picture" goes out as one album in the order you said them.
* A long press on the paperclip attaches your newest picture straight into the strip above the message box, without opening the attach menu. Keep holding and the one before it is added about every 0.7 seconds (ten at most), with a light tick each (none when vibration is off in the app), until you lift your finger. A short tap opens the attach menu as before. Stock XF does nothing on a long press of the paperclip, so nothing is lost. (1.6.0 opened the attach menu and ticked the screenshots there instead; 1.6.1 brought back the straight attach and draws the strip like the picker instead.)
* "Hold inserts" in the settings picks what the hold takes. "Most recent images (any folder)" takes the newest image on the phone by the date it was added, whatever folder it is in: a screenshot, a camera photo, a download, or a picture you just saved from a chat with Save to Gallery. "Most recent screenshots" takes only screenshots, as every version before 1.7.0 did. Hidden folders (a folder whose name starts with a dot, or one that holds a .nomedia file) are never taken from. If the any folder lookup finds nothing, the hold falls back to screenshots.
* The X on the strip removes them. The strip also empties when the message is sent, when you empty the message box by hand and when you leave the chat. After that, "screenshot" and "picture" start again from the newest.
* "Screenshot" and "picture" stay text after "a", "the", "this", "that", "my", "your", "of", "in", "take", "send", "see", "big", "profile", "nice" and similar words, so "take a screenshot", "send me a picture", "in the picture" and "look at this screenshot" are typed as words. They also stay text in the middle of a sentence ("take a picture of it") and while you edit a message. Said on its own after a pause, the word is the command.
* The lookup reads only the newest rows of your Screenshots folder (Pictures/Screenshots or DCIM/Screenshots) or of DCIM/Camera, and runs on a background thread. Screenshot, picture and the paperclip hold each have their own switch.
* A screenshot or photo you took a second ago is the one you get. Android takes a moment to finish saving a new screenshot (on a Pixel 6 Pro up to about a second, longer for a big one) and hides it while it does, so 1.4.0 could attach the one before it. Since 1.4.1 the lookup sees a picture that is still being saved, waits on its background thread until it is finished (checking every 0.1 second, at most 8 seconds) and attaches it. With nothing being saved there is no wait and no extra work.
* Saying "select all" as the last words selects everything in the message box and shows Cut, Copy and Paste, the same as tapping Select all. Keep dictating and your words replace the selection.
* "Select all" stays text after "to", "can", "you", "then", "tap", "press" and similar words, so "you can select all" and "then tap select all" are typed as words. It also stays text in the middle of a sentence ("select all of them"). It has its own switch.
* While you edit a message, "send" saves the edit, like the check mark.
* In slow mode it does what tapping the timer does.
* The Enter key still makes a new line, your Send by Enter setting stays as it is, and autocorrect and suggestions are unchanged.
* Work happens once when a chat's message field is created, a short text check once per finished word, and once for each spoken "send". Nothing runs per letter or per frame.

Settings: one switch each for send, paste, screenshot, picture, select all and the paperclip hold, all on at first, plus "Hold inserts" (most recent images from any folder at first).

## Install

1. Use the plugin build of Nagram XF and turn on the plugin engine (Settings, N Settings, Plugins).
2. Download `voice_send.plugin` from the latest release.
3. Send it to your Saved Messages, tap it and choose install. You can also use the plus button on the Plugins screen.
4. Make sure Voice Send is switched on.

## How it works

Gboard runs its voice "send" command only in apps on its own list (the official Telegram package is on it, XF's package is not) or in a field whose EditorInfo says it takes a send action: the IME action SEND, or the private IME option `handlesSendAction`. The plugin hooks `ChatActivityEnterView.createMessageEditText` and adds only `handlesSendAction` to the message field's private IME options, so `imeOptions` and the Enter key stay as they were. Gboard then calls `performEditorAction(IME_ACTION_SEND)`; a hook on `TextView.onEditorAction` answers it for that field only by pressing the send button (the done button while editing, the slow mode button while slow mode counts down).

Gboard does not always treat "send" as a command. Its modeless command detector (a MobileBERT intent classifier with a 0.95 threshold that also reads the text before the cursor and spoken punctuation) often scores "send" as dictation when it follows the sentence with no pause or comes right after a spoken punctuation command, and then simply types it as the last word. So the plugin also watches the message field's own InputConnection (`commitText` and `finishComposingText`, which arrive once per finished word or phrase, never per letter) for a finished chunk whose last word is "send". When that happens while voice typing is on, the word is dropped and the send button is pressed. When the field was empty a moment before (the whole message was one dictated chunk), XF is still fading the send button in, so the plugin calls the same `sendMessage` method the button calls. Voice typing counts as on while any app has the microphone open or closed it less than 3 seconds ago; a tiny Java class bundled in the plugin (`java/`, built into the plugin file by `build.py`) listens to Android's `AudioRecordingCallback`, which fires only when a recording starts or stops. Typed or swiped text never has the microphone on, so it is never taken as the command. Errors, and one line per caught "send", go to logcat under the tag `VoiceSend`.

Stock XF has no place where a photo can wait in the message box. Its attach sheet is a separate window, so opening it takes the keyboard away and ends the dictation, and it forgets its ticked photos every time it opens. So spoken "screenshot" and "picture" keep their own small tray per message box (`ShotTray`, the second Java class in the plugin). One MediaStore query for the newest rows of the Screenshots or Camera folder and the thumbnail run on a background thread; the UI thread only adds the thumbnail to the strip. Every way of sending the box (the send button, Enter, spoken "send", silent and scheduled send) ends in `ChatActivityEnterView.sendMessageInternal`; a hook there sees a tray that is not empty and hands the files to `ChatActivity.didSelectSearchPhotos`, the app's own call for picked photos, with the box text as the caption of the first one. Replies, topics and slow mode behave as they do for photos picked in the attach sheet. Text too long for a caption follows the album as its own message. While the tray is empty none of its listeners exist.

The paperclip hold (`ClipHold`, the third Java class) uses the button's own long press detection, then one posted repeat every 0.7 seconds while the finger is down, removed the moment it lifts. Each step adds one picture to the same tray spoken "screenshot" uses. The button's click listener is untouched. With "Hold inserts" on images of any folder, the step asks the tray for kind 2: the same single MediaStore Images query, sorted by date added and limited to the newest rows, without the folder filter, on the same background thread.

The strip borrows the picker's own parts: each picture is a square with the picker's `CheckBox2` tick (24 dp, the same arc and the theme's attach tick colours) numbered in attach order, the message panel's theme colour behind them and the app's own close icon. Pictures are 64 dp and get smaller when more would not fit across the chat (never under 24 dp; under 40 dp the ticks are left out). Every borrowed part is looked up by name and falls back to a plain look, so a new XF build cannot break the strip.

Spoken "select all" uses the same watch. When a finished chunk ends in those two words while voice typing is on, the words are dropped and the field gets Android's own set selection action for the whole text, which also brings up the Cut, Copy, Paste menu. The check is a short string test on the end of the chunk and reads at most the last 24 characters already in the box.

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

Version 1.7.0, Pixel 6 Pro test phone set to the 8 Pro's screen, same XF build and plugins, real touches, in Saved Messages, nothing sent:

* "Hold inserts" on most recent images: a picture saved from a chat with Save to Gallery (it lands in Pictures/Nagram) was the one a hold attached a few seconds later. After a real screenshot, a hold attached the screenshot first, then the saved picture. The attach menu never opened.
* The same with the keyboard open: a longer hold put the newest saved picture, the screenshot and the older saved picture in the strip, in that order.
* "Hold inserts" on most recent screenshots: the hold attached the two newest screenshots and skipped the newer saved pictures, as 1.6.1 did. The change applies at once, without a restart, and the settings row shows the current choice.
* Hidden folders: with a newer image indexed in a folder that holds a .nomedia file, the hold skipped it and took the next visible image.
* A short tap on the paperclip still opens the attach menu.
* Eleven minutes of mixed use in two runs: about 30 rounds of holds of 0.6, 1.3 and 3.5 seconds with the keyboard closed and open, leaving and reopening the chat every round, a real screenshot every eighth round, 136 pictures attached in all. XF kept the same process, the crash log stayed empty and the plugin logged no errors.
* Each lookup took 35 ms on average and 66 ms at most, on the background thread. Sixteen rounds of hold and clear without leaving the chat left the app's Java heap unchanged (60.2 MB before, 60.0 MB after). A four minute control run of the same rounds without any hold grew the app's memory at the same rate as the rounds with holds, so the hold adds nothing that stays.
* Not tested: sending the album (the send path is unchanged from 1.6.1), rotation, and switching between other chats.

Version 1.6.1, Pixel 6 Pro test phone set to the 8 Pro's screen, same XF build and plugins, real touches:

* Holds of 1.3 and 3.5 seconds put 2 and 5 screenshots straight into the strip, numbered 1 to 5 with the picker's blue ticks; the attach menu never opened.
* The send button sent the strip of two as one album in the strip's order.
* With the keyboard open the strip sits right above the message box; with eight pictures they shrink to fit across the chat.
* Ten minutes of mixed use: holds of 0.6, 1.3 and 3.5 seconds, the keyboard opened and closed, rotation to landscape and back with the strip showing, fast switching between busy topics. XF kept the same process the whole time, the crash log stayed empty and the plugin logged no errors.

Version 1.6.0, Pixel 6 Pro test phone set to the 8 Pro's screen, same XF build and plugins, real touches, in Saved Messages:

* A 2.6 second hold opened XF's own attach menu with 2 screenshots ticked; a 3.3 second hold ticked 3, numbered 1, 2, 3 at the top of the grid, with the stock counter and caption field.
* A 1.9 second hold ticked 2; the menu's own send button sent them as one album of two.
* Back with screenshots ticked showed XF's own Discard selection box, the same as picking them by hand.
* Ten minutes of mixed use: holds of 0.7 to 3.3 seconds, the keyboard opened and closed, fast switching between busy topics, rotation to landscape and back. XF kept the same process the whole time and the crash log stayed empty.

Version 1.5.0, Pixel 6 Pro test phone, Gboard voice typing listening, chunks replayed into the field's InputConnection, in Saved Messages:

* "Alpha beta gamma." then " Select all." as separate chunks: the two words were dropped, the whole text was selected and the Cut, Copy, Paste menu showed. The next chunk replaced the selection.
* "One two. Select all" finished through `setComposingText` plus `finishComposingText`: the same.
* "you can select all" and "then tap" followed by " select all" stayed text.
* With the microphone off, "One two. Select all" stayed text.
* A 10 minute run of typing, text menus, selection changes, rotation with the keyboard open, fast chat switching and repeated spoken "select all": same app process from start to end, empty crash log, no plugin errors.

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
