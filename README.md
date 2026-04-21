# Wearable Navigation Aid

We built a wearable obstacle detection system for visually impaired people using Meta Ray-Ban smart glasses, an Android phone and a computer vision server that speaks out loud what it sees.

This is a prototype. It works. And we are not done.

---

## Why we built this

A white cane is still the most common mobility tool for blind and visually impaired people. It works for the ground. It tells you nothing about a bollard at chest height, a car reversing out of a driveway, or a glass door you cannot see coming. People have been navigating around these gaps for decades with no real technological answer.

We thought we could try to change that.

The idea was straightforward: if someone is already wearing smart glasses, that camera feed is a live view of everything in front of them. All you need is something that watches that feed, understands what it sees and speaks up at the right moment. Not constantly. Not loudly. Just enough to matter.

That is what we built.

---

## What it does

The user wears Meta Ray-Ban smart glasses and carries an Android phone. The glasses stream a live first-person video feed to the phone. The phone samples frames from that stream and sends them over to a Python server running a YOLO-World object detection model. The server looks at each frame, decides if there is anything worth saying, and sends a short spoken phrase back to the phone. The phone reads it out loud.

Things like:

> *"Car dead ahead."*
> *"Stairs on the left."*
> *"Path clear."*

The whole loop runs in under a second.

What took us the most time to get right was not the detection. It was figuring out when to stay quiet. A system that talks too much is a system that gets switched off. So we built a filtering layer that tracks whether an object has appeared across multiple frames before saying anything, holds warnings briefly when something large suddenly disappears from view and only confirms "Path clear" after a few consecutive frames with nothing in the way. The goal was a system that earns every word it says.

---

## What we actually made happen

Getting this to work required solving problems across three very different areas at the same time.

On the hardware side we integrated with Meta's Device Access Toolkit to pull a live camera stream from the Ray-Ban glasses onto an Android device. That alone was not trivial. The SDK is in developer preview and the setup process has more than a few sharp edges including getting the right application credentials, enabling Developer Mode on the Meta AI app and tuning the stream buffer to reduce latency because in a navigation aid a delayed frame is worse than no frame.

On the Android side we built the bridge layer that takes that live stream, samples frames at a fixed interval, compresses and encodes each one and sends it over a WebSocket connection to the server. The phone also handles all the spoken output using Android's text-to-speech engine. It receives a short phrase from the server and reads it aloud immediately.

On the server side we set up a FastAPI service running YOLO-World, a zero-shot object detector that we did not need to retrain from scratch. We classified over 50 types of objects into three priority tiers. Critical hazards like people, cars, stairs and bollards always get announced first. Mid-level obstacles like chairs and shopping carts come second. Lower priority items like traffic lights and fences only speak if nothing more important is happening. We also built a frame history system so single-frame false positives never reach the user and a "ghost memory" that holds warnings for objects that briefly disappear from view mid-stride.

We also added an OCR cleaning layer for bus and transit signs so the system can read route information without speaking raw garbled text back at the user.

---

## Where things stand

Working right now:

- Live camera stream from Meta glasses to Android confirmed working on a real device
- Frame sampling and WebSocket transport to the server
- YOLO-World inference across 50+ object categories
- Priority tiering so the most dangerous thing always gets spoken first
- Frame stability and ghost memory filtering keeping the audio clean
- Android TTS reading detections back to the user in real time
- OCR cleaning for transit signs

Still in progress:

- Full latency benchmark across the complete pipeline on real hardware
- Navigation guidance beyond position labels — we want to move from "car on the left" toward something that helps the user actually decide what to do
- Drop-off and surface-change detection
- Haptic feedback as an alternative for noisy environments
- Field testing with visually impaired users

---

## What we are looking for

We built something we genuinely believe in. The prototype is real. The problem is real. What we need now is help making it better.

If you have experience in assistive technology, computer vision, accessibility design or mobile hardware we would like to talk. Not because we want someone to take over but because we know the gaps in our own knowledge and we would rather fill them with people who have been in this space than pretend they do not exist.

We are also actively looking for funding. Not to build a business plan in a pitch deck but to have the time and resources to do the field testing and iteration this project actually needs. Real users in real environments with real feedback. That is the next step and we cannot get there on spare time alone.

If you work at an assistive tech company, a research lab, an accessibility foundation or you are an investor who cares about this space, we want to hear from you.

---

## Get in touch

**GitHub:** [Wearable Navigation Aid](https://github.com/archithulsurkar/nav_aid)

Open an issue, start a discussion or reach out directly. We are paying attention.

