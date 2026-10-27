/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Mahboba Ariai - a2957620
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages using multiple worker threads
 * for concurrent frame processing. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
	private static final int WORKER_COUNT =
	        Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
	private static final int MAX_PENDING_FRAMES = WORKER_COUNT * 2;
	private ExecutorService frameProcessingExecutor;
	private final Queue<Future<PreparedFrame>> pendingFrames = new ArrayDeque<>();
	private volatile long playbackGeneration = 0;
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private final List<FrameProcessor> frameProcessors;
    private final java.util.concurrent.locks.ReentrantReadWriteLock
    frameProcessorLock = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
    
    //
    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private final ThreadLocal<JavaFXFrameConverter> workerConverters =
            ThreadLocal.withInitial(JavaFXFrameConverter::new);
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private boolean prepareNextFrameQueued; // True only when the event loop needs to call our prepareNextFrame method.
    private boolean playbackOpen; // True when playback is happening.
    private boolean videoDecodingFinished;
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<Character> requestedEffects,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = new ArrayList<>();

        for (char effect : requestedEffects) {
            switch (effect) {
                case 'n' -> frameProcessors.add(new FrameNumberer());
                case 's' -> frameProcessors.add(new FrameScratcher());
                case 'f' -> frameProcessors.add(new FrameFlickerer());
                case 'w' -> frameProcessors.add(new FrameBlackAndWhiter());
                case 'y' -> frameProcessors.add(new FrameYellower());
                case 'v' -> frameProcessors.add(new FrameVignetter());
                case 'd' -> frameProcessors.add(new FrameDuster());
                case 'j' -> frameProcessors.add(new FrameJitterer());
                case 'm' -> frameProcessors.add(new FrameMottler());
                case 'b' -> frameProcessors.add(new FrameBleeder());
                case 'p' -> frameProcessors.add(new FramePepperer());
                default -> throw new IllegalArgumentException(
                        "Unknown frame effect: " + effect);
            }
        }
//        frameProcessors.add(new FrameBleeder());
//        frameProcessors.add(new FrameScratcher());
//        frameProcessors.add(new FrameDuster());
//        frameProcessors.add(new FramePepperer());
//        frameProcessors.add(new FrameBlackAndWhiter());
//        frameProcessors.add(new FrameYellower());
//        frameProcessors.add(new FrameVignetter());
//        frameProcessors.add(new FrameFlickerer());
//        frameProcessors.add(new FrameJitterer());
//        frameProcessors.add(new FrameNumberer());
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
    	frameProcessingExecutor = Executors.newFixedThreadPool(WORKER_COUNT, task -> {
    	    Thread worker = new Thread(() -> {
    	        try {
    	            task.run();
    	        } finally {
    	            JavaFXFrameConverter workerConverter = workerConverters.get();
    	            workerConverter.close();
    	            workerConverters.remove();
    	        }
    	    });

    	    worker.setName("video-frame-worker");
    	    worker.setDaemon(true);
    	    return worker;
    	});
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
    	playbackGeneration++;
    	videoDecodingFinished = false;
    	cancelPendingFrameTasks();
        pendingAudio.clear();
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    private void prepareNextFrame() {
    	if (!playbackOpen || videoDecodingFinished
    	        || isFrameProcessingQueueFull()) {
    	    return;
    	}
    	
    	try {
    	    DecodedFrame decoded = decodeNextVideoFrame();

    	    if (decoded != null) {
    	        submitDecodedFrame(decoded);
    	    }
    	} catch (Exception e) {
    	    handlePlaybackError(e);
    	}
    }
    
    private DecodedFrame decodeNextVideoFrame() throws Exception {
        while (playbackOpen && !videoDecodingFinished) {
            Frame frame = grabFrame();

            if (frame == null) {
                videoDecodingFinished = true;
                return null;
            }

            long timestampUs = grabber.getTimestamp();

            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();

                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);

            long relativeTimestampUs =
                    Math.max(0, timestampUs - firstTimestampUs);
            long logicalTimestampUs =
                    logicalPlaybackBaseUs + relativeTimestampUs;
            long targetTimeNs =
                    playbackStartNs + relativeTimestampUs * 1_000L;

            return new DecodedFrame(
                    frame.clone(),
                    info,
                    frameNumber,
                    timestampUs,
                    logicalTimestampUs,
                    targetTimeNs,
                    getPlaybackGeneration());
        }

        return null;
    }
    
    private PreparedFrame readNextVideoFrame() throws Exception {
        while (playbackOpen) {
            Frame frame = grabFrame();
            if (frame == null) {
                videoDecodingFinished = true;
                return null;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);

            Frame frameCopy = frame.clone();
            processFrame(frameCopy, info);

            Image image = converter.convert(frameCopy);
            long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
            long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
            long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

            return new PreparedFrame(
                    image,
                    frameNumber,
                    timestampUs,
                    logicalTimestampUs,
                    targetTimeNs,
                    System.nanoTime());
        }

        return null;
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. We have three jobs: (1) keep audio flowing if sound is on,
    // (2) display a frame if it is due and (3) prepare the next frame if we're in the window after the previous frame
	// has gone to the display. Frames are processed concurrently by background
	// worker threads and collected in submission order for playback.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }
        
        try {
            collectCompletedFrame();
        } catch (Exception e) {
            handlePlaybackError(e);
            return;
        }
        
        // Task (1)
        writeDueAudio(now);

        // Task (2)
        if (!pauseRequested && preparedFrame != null
                && preparedFrame.targetTimeNs() <= now) {
            displayPreparedFrame(now);
        }

	     // Task (3). Decode and submit the next frame for background processing
	     // when playback is active and the pending frame queue has capacity.
	     // The bounded queue prevents too many frames from being scheduled.
        if (!pauseRequested && !videoDecodingFinished
                && !isFrameProcessingQueueFull()) {
            prepareNextFrame();
        }
        if (videoDecodingFinished && preparedFrame == null
                && pendingFrames.isEmpty()) {
            finishPlayback();
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
    	playbackGeneration++;
    	videoDecodingFinished = false;
    	if (frameProcessingExecutor != null) {
    	    frameProcessingExecutor.shutdownNow();
    	    frameProcessingExecutor = null;
    	}
    	cancelPendingFrameTasks();
    	
        playbackTimer.stop();
        playbackOpen = false;
        preparedFrame = null;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        frameProcessorLock.readLock().lock();

        try {
            for (FrameProcessor processor : frameProcessors) {
                processor.process(frame, info);
            }
        } finally {
            frameProcessorLock.readLock().unlock();
        }
    }
    
    private PreparedFrame processDecodedFrame(DecodedFrame decoded) throws Exception {
        Frame frame = decoded.frame();

        try {
        	if (Thread.currentThread().isInterrupted()
        	        || !isCurrentPlaybackGeneration(decoded.generation())) {
        	    return null;
        	}
            processFrame(frame, decoded.info());

            Image image = workerConverters.get().convert(frame);

            return new PreparedFrame(
                    image,
                    decoded.frameNumber(),
                    decoded.mediaTimestampUs(),
                    decoded.logicalTimestampUs(),
                    decoded.targetTimeNs(),
                    System.nanoTime());

        } finally {
            frame.close();
        }
    }
    private void submitDecodedFrame(DecodedFrame decoded) {
        java.util.concurrent.FutureTask<PreparedFrame> task =
                new java.util.concurrent.FutureTask<>(
                        () -> processDecodedFrame(decoded)) {

                    private boolean started;

                    @Override
                    public void run() {
                        synchronized (this) {
                            if (isCancelled()) {
                                return;
                            }
                            started = true;
                        }

                        super.run();
                    }

                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        synchronized (this) {
                            boolean cancelled = super.cancel(mayInterruptIfRunning);

                            if (cancelled && !started) {
                                try {
                                    decoded.frame().close();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }

                            return cancelled;
                        }
                    }
                };

                try {
                    frameProcessingExecutor.execute(task);
                    pendingFrames.add(task);
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    task.cancel(false);
                    throw e;
                }
    }
    
    private Future<PreparedFrame> submitFrameProcessingTask(
        java.util.concurrent.Callable<PreparedFrame> task) {

        if (frameProcessingExecutor == null) {
            throw new IllegalStateException("Frame processing executor is not running");
        }

        return frameProcessingExecutor.submit(task);
    }
    
    private boolean isFrameProcessingQueueFull() {
        int bufferedFrames = pendingFrames.size();

        if (preparedFrame != null) {
            bufferedFrames++;
        }

        return bufferedFrames >= MAX_PENDING_FRAMES;
    }
    
    private PreparedFrame getNextCompletedFrame() throws Exception {
        while (!pendingFrames.isEmpty()) {
            Future<PreparedFrame> nextFrame = pendingFrames.peek();

            if (!nextFrame.isDone()) {
                return null;
            }

            pendingFrames.remove();

            if (nextFrame.isCancelled()) {
                continue;
            }

            return nextFrame.get();
        }

        return null;
    }
    
    private void collectCompletedFrame() throws Exception {
        if (preparedFrame != null) {
            return;
        }

        PreparedFrame completed = getNextCompletedFrame();

        if (completed != null) {
            preparedFrame = completed;
        }
    }
    
    private void cancelPendingFrameTasks() {
        for (Future<PreparedFrame> future : pendingFrames) {
            future.cancel(true);
        }

        pendingFrames.clear();
    }
    
    private boolean isCurrentPlaybackGeneration(long generation) {
        return generation == playbackGeneration;
    }
    
    private long getPlaybackGeneration() {
        return playbackGeneration;
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        frameProcessorLock.writeLock().lock();

        try {
            for (FrameProcessor processor : frameProcessors) {
                processor.initialise(videoInfo);
            }
        } finally {
            frameProcessorLock.writeLock().unlock();
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }
    
    private record DecodedFrame(
            Frame frame,
            InfoFrame info,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long generation) {
    }
    
    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
