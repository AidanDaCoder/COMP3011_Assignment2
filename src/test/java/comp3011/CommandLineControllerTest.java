/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests the command line parsing. None of these start the player. They check which effects are built, in which order,
 * and which messages come back for bad input.
 */
class CommandLineControllerTest {
    @TempDir
    Path folder;

    private String video;

    @BeforeEach
    void createVideoFile() throws IOException {
        // The parser only checks that the file exists, so any file will do.
        video = Files.writeString(folder.resolve("clip.mp4"), "not really a video").toString();
    }

    private CommandLineController parse(String... options) {
        List<String> args = new ArrayList<>(List.of(options));
        args.add(video);
        return new CommandLineController(args.toArray(new String[0]));
    }

    private static List<Class<?>> classesOf(CommandLineController controller) {
        return controller.getFrameProcessors().stream().<Class<?>>map(Object::getClass).toList();
    }

    @ParameterizedTest
    @CsvSource({
            "-n, --number-frames, FrameNumberer",
            "-s, --scratch-frames, FrameScratcher",
            "-f, --flicker-frames, FrameFlickerer",
            "-w, --black-and-white, FrameBlackAndWhiter",
            "-y, --yellow-frames, FrameYellower",
            "-v, --vignette-frames, FrameVignetter",
            "-d, --dust-frames, FrameDuster",
            "-j, --jitter-frames, FrameJitterer",
            "-m, --mottle-frames, FrameMottler",
            "-b, --bleed-frames, FrameBleeder",
            "-p, --pepper-frames, FramePepperer" })
    void shortAndLongFlagsBuildTheSameEffect(String shortFlag, String longFlag, String className) {
        CommandLineController shortForm = parse(shortFlag);
        CommandLineController longForm = parse(longFlag);

        assertEquals(1, shortForm.getFrameProcessors().size());
        assertEquals(className, shortForm.getFrameProcessors().get(0).getClass().getSimpleName());
        assertEquals(classesOf(shortForm), classesOf(longForm));
        assertTrue(shortForm.shouldLaunchApplication());
    }

    @Test
    void effectsAreBuiltInCommandLineOrder() {
        CommandLineController controller = parse("-nsfwyvdjmbp");

        assertEquals(
                List.of(FrameNumberer.class, FrameScratcher.class, FrameFlickerer.class, FrameBlackAndWhiter.class,
                        FrameYellower.class, FrameVignetter.class, FrameDuster.class, FrameJitterer.class,
                        FrameMottler.class, FrameBleeder.class, FramePepperer.class),
                classesOf(controller));
    }

    @Test
    void flagsMayBeMixedAndRepeated() {
        CommandLineController controller = parse("-ss", "--number-frames", "-n");

        assertEquals(
                List.of(FrameScratcher.class, FrameScratcher.class, FrameNumberer.class, FrameNumberer.class),
                classesOf(controller));
    }

    @Test
    void aRepeatedFlagGivesIndependentObjects() {
        CommandLineController controller = parse("-nn");

        assertNotSame(controller.getFrameProcessors().get(0), controller.getFrameProcessors().get(1));
    }

    @Test
    void noEffectsWhenNoFlagsAreGiven() {
        CommandLineController controller = parse();

        assertTrue(controller.getFrameProcessors().isEmpty());
        assertEquals(0, controller.getExitCode());
    }

    @Test
    void optionsAreRecognised() {
        CommandLineController controller = parse("-a", "-x", "-2");

        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(2, controller.getDisplayId());
        assertEquals(video, controller.getVideoFile().getPath());
    }

    @Test
    void longOptionsAreRecognised() {
        CommandLineController controller = parse("--audio", "--maximise", "--monitor-1");

        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(1, controller.getDisplayId());
    }

    @Test
    void anUnknownOptionIsAnError() {
        CommandLineController controller = parse("-q");

        assertEquals("Unknown option: -q", controller.getErrorMessage());
        assertEquals(1, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void aBundleWithAnUnknownLetterIsAnError() {
        CommandLineController controller = parse("-nsq");

        assertEquals("Unknown option: -nsq", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void aMissingVideoFileIsAnError() {
        CommandLineController controller = new CommandLineController(new String[] { "-n" });

        assertEquals("No video file specified.", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void aVideoFileThatDoesNotExistIsAnError() {
        String missing = folder.resolve("missing.mp4").toString();
        CommandLineController controller = new CommandLineController(new String[] { missing });

        assertEquals("File not found: " + missing, controller.getErrorMessage());
        assertNull(controller.getVideoFile());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void moreThanOneVideoFileIsAnError() {
        CommandLineController controller = new CommandLineController(new String[] { video, video });

        assertEquals("Usage: VideoPlayer [options] [video-file]", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void twoDifferentDisplaysAreAnError() {
        CommandLineController controller = parse("-1", "-2");

        assertEquals("Only one display option can be used", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void helpNeedsNoVideoFile() {
        CommandLineController controller = new CommandLineController(new String[] { "--help" });

        assertNull(controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }
}
