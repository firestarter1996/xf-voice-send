# 🎙️🗣️📤 Voice Send

A plugin for Nagram XF. While you dictate with Gboard voice typing, say "send" and the message goes out, exactly like tapping the send button.

## Why I made it

Gboard's voice typing understands "send" in Telegram, WhatsApp and a few other apps. In Nagram XF it answers "Command not available in this app" or types the word, so every dictated message still needs a tap. I wanted to finish a message by voice.

## What it changes

* Saying "send" while dictating presses the send button for you: replies, topics, captions, silent send and scheduled messages behave as a tap would.
* It fires on the first try, even when you say "send" straight after the sentence with no pause, or right after a spoken punctuation command ("close parenthesis", "period", "question mark" and so on). In those cases Gboard on its own types the word instead; the plugin removes it and sends. The word never lands in the message.
* What counts as the command: "send" as the last word of what you just dictated, whatever word comes before it, in any case, with or without a period after it. "That looks good send" goes out as "that looks good".
* What stays text: typed or swiped "send", "send" in the middle ("send it to Tim") and longer words ("resend").
* While you edit a message, "send" saves the edit, like the check mark.
* In slow mode it does what tapping the timer does.
* The Enter key still makes a new line, your Send by Enter setting stays as it is, and autocorrect and suggestions are unchanged.
* Work happens once when a chat's message field is created, a short text check once per finished word, and once for each spoken "send". Nothing runs per letter or per frame.

Setting: one switch turns it off.

## Install

1. Use the plugin build of Nagram XF and turn on the plugin engine (Settings, N Settings, Plugins).
2. Download `voice_send.plugin` from the latest release.
3. Send it to your Saved Messages, tap it and choose install. You can also use the plus button on the Plugins screen.
4. Make sure Voice Send is switched on.

## How it works

Gboard runs its voice "send" command only in apps on its own list (the official Telegram package is on it, XF's package is not) or in a field whose EditorInfo says it takes a send action: the IME action SEND, or the private IME option `handlesSendAction`. The plugin hooks `ChatActivityEnterView.createMessageEditText` and adds only `handlesSendAction` to the message field's private IME options, so `imeOptions` and the Enter key stay as they were. Gboard then calls `performEditorAction(IME_ACTION_SEND)`; a hook on `TextView.onEditorAction` answers it for that field only by pressing the send button (the done button while editing, the slow mode button while slow mode counts down).

Gboard does not always treat "send" as a command. Its modeless command detector (a MobileBERT intent classifier with a 0.95 threshold that also reads the text before the cursor and spoken punctuation) often scores "send" as dictation when it follows the sentence with no pause or comes right after a spoken punctuation command, and then simply types it as the last word. So the plugin also watches the message field's own InputConnection (`commitText` and `finishComposingText`, which arrive once per finished word or phrase, never per letter) for a finished chunk whose last word is "send". When that happens while voice typing is on, the word is dropped and the send button is pressed. When the field was empty a moment before (the whole message was one dictated chunk), XF is still fading the send button in, so the plugin calls the same `sendMessage` method the button calls. Voice typing counts as on while any app has the microphone open or closed it less than 3 seconds ago; a tiny Java class bundled in the plugin (`java/`, built into the plugin file by `build.py`) listens to Android's `AudioRecordingCallback`, which fires only when a recording starts or stops. Typed or swiped text never has the microphone on, so it is never taken as the command. Errors, and one line per caught "send", go to logcat under the tag `VoiceSend`.

## Build

`voice_send.plugin` is `voice_send.src.py` plus the compiled MicWatch class. `build.py` lists the javac and d8 steps and writes the plugin file.

## Tested

Nagram XF 12.10.1 plugin build on a Pixel 6 Pro with Gboard 18.5:

* The message field's EditorInfo carries `handlesSendAction`, the action and the no enter action flag are unchanged.
* `performEditorAction(IME_ACTION_SEND)` on the field's own InputConnection sent the text and cleared the field.
* In edit mode the same action saved the edit.
* With Gboard voice typing listening, the field received "VS voice test", then ")", then " send" as separate commits: the message went out without the word "send".
* The same chunk finished through `setComposingText` plus `finishComposingText` also sent.
* With the microphone off, ") send" and "send" stay as typed text, and "please send" never triggers.
* The extra hook costs about 0.15 ms per finished word.

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
