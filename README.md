# Cap-Man Dialer

A replacement phone app for Android: dialer, contacts, call history and a full
call screen — with an arcade twist on answering.

## The call screen

An incoming call shows two things you can grab and one destination each:

* Drag the **chomper** onto the green handset → the call is **answered**.
  It eats the pellets along the way.
* Drag the **skull** onto the chomper → the call is **rejected**.
  The chomper dies, the screen closes immediately.

Two independent drags, one destination each, so a fast diagonal swipe can never
lock onto the wrong action.

## Features

* Recents grouped per number, with an expandable "+2" for repeat calls
* A "Missed" filter that hides anything you have already called back
* Horizontal favorites strip with drag-to-reorder
* Per-contact notes, shown on screen the next time that person calls
* Per-contact ringtones, including your own music
* Reject with a canned SMS reply, or "remind me in X minutes"
* System-wide number blocking (uses Android's own blocked-number list)
* Pick the call audio output: speaker, earpiece, wired headset or any
  connected Bluetooth device, by name
* Motion shortcuts: raise to ear to answer, flip face down to silence,
  power key to silence and blank the screen
* Proximity blanking during a call
* Contacts import/export as .vcf
* Five selectable launcher icons
* Light and dark themes
* A first-run tour that lets you practise the gesture on a fake call

## What it deliberately does not do

* **Call recording of the other party.** Since Android 10 an ordinary app cannot
  reach the call audio stream; the microphone yields silence during a call. The
  record button is therefore hidden unless the device is rooted or the app is a
  privileged system app.
* **Modify your call log.** Only READ_CALL_LOG is requested.
* **Reach the network.** There is no INTERNET permission.

## Build

```
./gradlew assembleDebug
```

Requires the Android SDK (compileSdk 34, minSdk 26). No dependencies beyond
`androidx.recyclerview`.

## Licence

GPL-3.0-or-later. See LICENSE.
