# 🎙️🗣️📤 Voice Send

A plugin for Nagram XF. While you dictate with Gboard voice typing, say "send" and the message goes out, exactly like tapping the send button.

## Why I made it

Gboard's voice typing understands "send" in Telegram, WhatsApp and a few other apps. In Nagram XF it answers "Command not available in this app" or types the word, so every dictated message still needs a tap. I wanted to finish a message by voice.

## What it changes

* Saying "send" while dictating presses the send button for you: replies, topics, captions, silent send and scheduled messages behave as a tap would.
* While you edit a message, "send" saves the edit, like the check mark.
* In slow mode it does what tapping the timer does.
* The Enter key still makes a new line, your Send by Enter setting stays as it is, and autocorrect and suggestions are unchanged.
* Work happens once when a chat's message field is created and once for each spoken "send". Nothing runs while you type.

Setting: one switch turns it off.

## Install

1. Use the plugin build of Nagram XF and turn on the plugin engine (Settings, N Settings, Plugins).
2. Download `voice_send.plugin` from the latest release.
3. Send it to your Saved Messages, tap it and choose install. You can also use the plus button on the Plugins screen.
4. Make sure Voice Send is switched on.

## How it works

Gboard runs its voice "send" command only in apps on its own list (the official Telegram package is on it, XF's package is not) or in a field whose EditorInfo says it takes a send action: the IME action SEND, or the private IME option `handlesSendAction`. The plugin hooks `ChatActivityEnterView.createMessageEditText` and adds only `handlesSendAction` to the message field's private IME options, so `imeOptions` and the Enter key stay as they were. Gboard then calls `performEditorAction(IME_ACTION_SEND)`; a hook on `TextView.onEditorAction` answers it for that field only by pressing the send button (the done button while editing, the slow mode button while slow mode counts down). Errors go to logcat under the tag `VoiceSend`.

## Tested

Nagram XF 12.10.1 plugin build on a Pixel 6 Pro with Gboard 18.5:

* The message field's EditorInfo carries `handlesSendAction`, the action and the no enter action flag are unchanged.
* `performEditorAction(IME_ACTION_SEND)` on the field's own InputConnection sent the text and cleared the field.
* In edit mode the same action saved the edit.

## License

MIT

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=firestarter1996/xf-voice-send&type=Date)](https://star-history.com/#firestarter1996/xf-voice-send&Date)
