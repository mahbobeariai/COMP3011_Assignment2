# COMP3011 Assignment 2 - Java Local Project - Multithreaded Video Player

## Overview
This project transforms a simple JavaFX video player into a more sophisticated player that can sit quite happily in a professional post production compositing suite — still runnable from the command line — but responding to command line arguments to add/compose/remove the effects the artists wants in a more stackable way by using multiple threads during playback.

[Assignment Specification](https://learn.adelaide.edu.au/courses/30747/assignments/94505)

## Multithreading Design
- VideoPlayerModel is responsible for controlling the playback and background frames processing.
- Concurrently processing worker threads to process frames is managed by an ExecutorService.
- Worker threads process frames concurrently using `Math.max(1, availableProcessors - 2)`.
- The frames are ordered and coordinated between playback-states to avoid the display of outdated or incorrect frames.

## Performance Optimisations
- Minimised unnecessary frame-scheduling overhead.
- Maintain optimum CPU usage and GUI responsiveness.

## Command Line Options
Several effects can be combined and run in order and the user could process videos directly from the command line. Using the following Command line options:
`Usage: VideoPlayer [options] [video-file]`

### General Options
- `-h`, `--help` — Show the help message
- `-a`, `--audio` — Play audio
- `-x`, `--maximise` — Open the player maximised
- `-1`, `--monitor-1` — Open on display 1
- `-2`, `--monitor-2` — Open on display 2

### Frame Processors
- `-n`, `--number-frames` — Render frame numbers
- `-s`, `--scratch-frames` — Render film scratches
- `-f`, `--flicker-frames` — Randomly dim frames
- `-w`, `--black-and-white` — Convert to black and white
- `-y`, `--yellow-frames` — Apply a warmer colour temperature
- `-v`, `--vignette-frames` — Darken frame edges
- `-d`, `--dust-frames` — Render dust and hair
- `-j`, `--jitter-frames` — Randomly displace frames
- `-m`, `--mottle-frames` — Add cloudy mottling
- `-b`, `--bleed-frames` — Bleed light into frames
- `-p`, `--pepper-frames` — Add dark spots and blotches

## Build and Run
Compile using `mvn compile` and run using `mvn javafx:run`, supplying a video file through the `javafx.args` Maven property.
