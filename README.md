# COMP3011 Assignment 2 Video Player

A JavaFX and JavaCV video player that applies film style effects to each frame, made multi threaded for Assignment 2.
Author: Aidan Chan (A1924235). The starter code was supplied by Simon Ratcliffe for Adelaide University COMP3011.

## Build, run and test

```
mvn compile javafx:run '-Djavafx.args=-a -nsfwyvdjmbp path/to/video.mp4'
mvn test            # unit tests
mvn verify          # compile, test and package
mvn -Pfat-jar package   # runnable jar, see VideoPlayerAppLauncher
```

Options are `-h`, `-a` (audio), `-x` (maximise), `-1` and `-2` (display). The effects are applied in command line order and may be repeated. Each has a short and a long flag (`-n` or `--number-frames`). Run with `--help` for the list. Short flags can be bundled, so `-nssnfwyvdjmbp` means numberer, scratcher twice, numberer, flickerer and so on.

To add an effect, write a `FrameProcessor` subclass and add one row to the `EFFECTS` table in
`CommandLineController`. The flags, the help text and the parser all come from that table.

## What was slow

The starter player did everything on the JavaFX thread, once per 60 Hz tick: grab a frame, run the effect chain, convert it to an Image and show it. At 23.98 fps a frame has 41.7 ms, and on my MacBook Air (10 cores) the player fell behind at about 20 effects. Measuring first showed where the time goes (milliseconds per frame, original player):

| Part | Cost |
|---|---|
| Decode (grab) | 1 to 4 |
| Convert to Image | about 1 |
| Vignetter | 4.7 |
| Bleeder | 3.5 to 5.3 |
| BlackAndWhiter | 3.0 |
| Mottler | 1.5 to 3.8 |
| Yellower | 1.65 |
| Flickerer | 1.05 |
| Pepperer, Jitterer, Duster, Scratcher, Numberer | each under 0.2 |

Eleven effects cost about 17.5 ms, and 33 cost about 50 ms, which is more than one frame. About 7 to 10 ms more was lost waiting for the animation tick. Only six effects matter, and all six are loops over every pixel.

## Design

There are two layers of concurrency.

1. **A preparer thread** (`FramePreparer`) does the work of getting a frame ready: grab, effects, convert. It puts finished frames on a queue of four (`ArrayBlockingQueue<PreparedFrame>`). The JavaFX thread only takes frames off the queue and shows them when they are due, so decoding and effects no longer block drawing.
2. **Row parallel effects** (`RowParallel`). The six expensive effects split their loops by rows (or by rows of blocks) and run the bands on a pool of (number of cores minus 2) worker threads. The preparer thread waits until every band is done and only then moves on to the next effect.

```
FX thread  <--- ArrayBlockingQueue (4 frames) ---  frame-preparer thread
 shows frames,                                      grab -> effect 1 -> effect 2 -> ... -> convert
 owns the clock                                     each heavy effect: RowParallel.forRows
                                                      -> row-worker-0 ... row-worker-N
```

Frames still go through the effects one at a time and in order, so the output is exactly what the single threaded player produced. This matters because the effects are deterministic (seeded by media name, effect position and frame number) and the grader compares frames against a reference player.

### Who owns what

| State | Owner | How it is shared |
|---|---|---|
| FFmpeg grabber, effect chain, `initialise` calls, `AudioPlayer.copySamples`, the converter | preparer thread only | never touched by another thread while it runs |
| Playback clock (`firstTimestampUs`, `playbackStartNs`, `pauseStartedNs`), display, audio write and flush | JavaFX thread only | set when the first frame of a segment is taken |
| Prepared video frames | preparer produces, FX consumes | `ArrayBlockingQueue`, and the Image is not changed after it is queued |
| Audio chunks | preparer produces, FX consumes | `ConcurrentLinkedQueue` of `PendingAudio` |
| Stop request | FX writes, preparer reads | `volatile boolean`, waits use timeouts so a stop is always noticed |
| Audio on/off | FX writes, preparer reads | `volatile boolean` |
| Pixels of one frame during an effect | the row worker holding that band | bands cover disjoint rows, and the caller waits for all of them (`Future.get` gives the happens before edge) |
| Per effect scratch (Bleeder masks, Jitterer source pixels, Mottler damage masks) | the preparer thread | one chain exists, and only the preparer runs it |

Why a single effect chain: the effects use static instance counters for their seeds, so building a second chain would change the output, and Bleeder, Jitterer and Mottler keep per frame scratch arrays in the instance, so one instance cannot run two frames at once. That ruled out a chain per worker and made parallelism inside a frame the
right fit.

### Stopping and seeking

The preparer is stopped with a flag, not `Thread.interrupt`, because interrupting a thread inside native FFmpeg code is unsafe. `stop()` joins the thread, so a seek can reposition the grabber knowing nothing else is using it. A seek stops the preparer, calls `setTimestamp`, clears the queues and the clock, flushes audio and restarts the preparer. While paused the preparer fills the queue and blocks, and the FX thread holds the frames, showing only the first frame after a seek. At the end of the video the preparer queues an end marker (`PreparedFrame.END`).

### The mottler change

Mottler used to keep a block's three colour scale indices in instance fields, which would be a race between row workers. Each block now returns its three indices packed in one int, and nothing is written to the instance.

## What I tried that did not work

I first built a pipeline (branch `multithreaded-pipeline`): a producer thread, one thread per effect joined by queues, and a converter thread, with frames copied by `Frame.clone()` because the grabber reuses its buffer. On my Mac it kept 33 effects on time, but on TITAN (8 vCPUs, slower per core) it failed: tags `stage-b-complete` dropped about 100 frames at 33 effects, `stage-b2-complete` (effects grouped into a few stages) froze at about frame 199, and `stage-b3-complete` lost 59 frames in a row at 11 effects. I could not see the entry's error output on TITAN and could not reproduce any of the failures locally, even with `-XX:ActiveProcessorCount=8`. My prediction is that about 37 busy threads and the per frame copies starved the display on a shared machine.
Moving the parallelism inside the effects removed the extra threads, the copies and the queues between stages, and passed.

## Results (TITAN, 8 vCPUs)

| Version | Performance | Startup | Cores used |
|---|---|---|---|
| One preparer thread (`stage-a-complete`) | 102.42% | 100% | 2.88 |
| Row parallel effects (`row-parallel-v1`) | 260.52% | 90% | 5.89 |
| Same plus tests and flag table | 257.90% | 90% | 5.67 |

Performance is the entry's frame rate divided by the reference player's at the first load where the entry can no longer keep up. For the last two rows that was 33 effects, where the entry ran at 19.65 and 16.37 fps against 7.54 and 6.35 fps for the reference. The machine is noisy, since both rates moved by about 16% between runs, but the ratio stayed close. No frames were missed in any run, and the frame comparisons against the reference all passed.

## Testing

`mvn test` runs 31 tests with JUnit 5:

* `CommandLineControllerTest` checks every short and long flag, the order of effects, bundled and repeated flags,
  independent objects for repeated flags, the options, and each error message.
* `RowParallelTest` checks the bands cover every row once with no gaps, every row is visited exactly once, the
  call waits for all the work, and an exception in a band reaches the caller.
* `ParallelEffectsTest` runs the six parallel effects on a random frame twice, once with `RowParallel.forceSequential`
  on and once off, and checks the pixels are identical at four frame numbers.

I also checked by hand with the 11 effect string: audio and the A key, pause and resume, seeking with the arrow keys
playing and paused, Home, resize, Esc, and the end of the video.

## Known limits

* An error callback from an old preparer that arrives after a seek could close the new session.
* Startup scored 90% on TITAN for the row parallel versions against 100% for the single preparer version. The startup figure seems to come from the run with one cheap effect, which does not use the parallel code.
* With two or fewer cores `RowParallel` has no workers and the effects run on the preparer thread, which is correct but no faster.
* Decode, conversion and the cheap effects are still on one thread, which limits the speed up at high effect counts.

## Attribution

* The starter player, effects and project layout are by Simon Ratcliffe, in collaboration with GPT-5.6 Terra  (see the headers of each file).
* I changed `FramePreparer` and `PreparedFrame` and `PendingAudio` (new), `VideoPlayerModel`, `CommandLineController`, `RowParallel` (new), the six effects `FrameVignetter`, `FrameBlackAndWhiter`, `FrameYellower`, `FrameFlickerer`, `FrameBleeder` and `FrameMottler`, `pom.xml`, and the tests in `src/test`. My name is in each header.
