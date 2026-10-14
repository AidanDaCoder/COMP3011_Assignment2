/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Aidan Chan A1924235
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;

import java.util.Map;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
	
	//a supplier creates a fresh processor on each call, so a repeated flag
	// gets two independent objects, not one shared one.
	private static final Map<Character, Supplier<FrameProcessor>> SHORT_FLAGS = Map.ofEntries(
			 Map.entry('n', FrameNumberer::new),
		     Map.entry('s', FrameScratcher::new),
		     Map.entry('f', FrameFlickerer::new),
		     Map.entry('w', FrameBlackAndWhiter::new),
		     Map.entry('y', FrameYellower::new),
		     Map.entry('v', FrameVignetter::new),
		     Map.entry('d', FrameDuster::new),
		     Map.entry('j', FrameJitterer::new),
		     Map.entry('m', FrameMottler::new),
		     Map.entry('b', FrameBleeder::new),
		     Map.entry('p', FramePepperer::new));
	
	private static final Map<String, Supplier<FrameProcessor>> LONG_FLAGS = Map.ofEntries(
			 Map.entry("--number-frames", FrameNumberer::new),
			 Map.entry("--scratch-frames", FrameScratcher::new),
			 Map.entry("--flicker-frames", FrameFlickerer::new),
			 Map.entry("--black-and-white", FrameBlackAndWhiter::new),
			 Map.entry("--yellow-frames", FrameYellower::new),
			 Map.entry("--vignette-frames", FrameVignetter::new),
			 Map.entry("--dust-frames", FrameDuster::new),
		     Map.entry("--jitter-frames", FrameJitterer::new),
		     Map.entry("--mottle-frames", FrameMottler::new),
		     Map.entry("--bleed-frames", FrameBleeder::new),
		     Map.entry("--pepper-frames", FramePepperer::new));
	
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    
    private final List<FrameProcessor> frameProcessors = new ArrayList<>();

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }
    
    public List<FrameProcessor> getFrameProcessors() {
    	return frameProcessors;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                helpRequested = true;
            } else if ("-a".equals(arg) || "--audio".equals(arg)) {
                audioRequested = true;
            } else if ("-x".equals(arg) || "--maximise".equals(arg)) {
                maximiseRequested = true;
            } else if ("-1".equals(arg) || "--monitor-1".equals(arg)) {
                setDisplayId(1);
            } else if ("-2".equals(arg) || "--monitor-2".equals(arg)) {
                setDisplayId(2);
            } else if (LONG_FLAGS.containsKey(arg)) { 
            	frameProcessors.add(LONG_FLAGS.get(arg).get());
            } else if (isShortFlagGroup(arg)) {
            	for (int i = 1; i < arg.length(); i++) {
            		frameProcessors.add(SHORT_FLAGS.get(arg.charAt(i)).get());
            	}
            } else if (arg.startsWith("-")) {
                errorMessage = "Unknown option: " + arg;
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }
    
    //true for a single-dash token made only of known effect letters
    // e.g. "-n" or a bundle like "-nssnfwyvdjmbp".
    private boolean isShortFlagGroup (String arg) {
    	if (arg.length() < 2 || arg.charAt(0) != '-' || arg.charAt(1) == '-') {
    		return false;
    	}
    	
    	for (int i = 1; i < arg.length(); i++) {
    		if (!SHORT_FLAGS.containsKey(arg.charAt(i))) {
    			return false;
    		}
    	}
    	return true;
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frames  Darken the frame edges");
        System.out.println("  -d, --dust-frames      Render dust and hair marks");
        System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println("  -b, --bleed-frames     Bleed light into frames");
        System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
