package smithereen.media;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import smithereen.libvips.VipsImage;
import smithereen.model.media.MediaFileType;
import smithereen.model.media.MediaKind;
import smithereen.model.media.VideoMetadata;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the video pipeline on clips that ffmpeg generates itself. Skipped when ffmpeg or libvips is not available.
 */
public class VideoProcessorTest{
	private static boolean available;

	@TempDir
	Path tmp;

	@BeforeAll
	static void checkTools(){
		boolean vips;
		try{
			new VipsImage("/nonexistent/file");
			vips=false;
		}catch(IOException expected){
			vips=true;
		}catch(Throwable x){
			vips=false;
		}
		available=vips && FFmpeg.isAvailable();
	}

	/**
	 * A test-pattern clip with a sine tone, {@code seconds} long.
	 */
	private File clip(String name, int width, int height, int seconds, boolean withAudio) throws IOException{
		File f=tmp.resolve(name).toFile();
		List<String> cmd=withAudio
				? List.of("ffmpeg", "-nostdin", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration="+seconds+":size="+width+"x"+height+":rate=25",
						"-f", "lavfi", "-i", "sine=frequency=440:duration="+seconds, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", f.getAbsolutePath())
				: List.of("ffmpeg", "-nostdin", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration="+seconds+":size="+width+"x"+height+":rate=25",
						"-c:v", "libx264", "-pix_fmt", "yuv420p", f.getAbsolutePath());
		FFmpeg.run(cmd, 120);
		return f;
	}

	@Test
	void probeReadsSizeDurationAndAudio() throws Exception{
		Assumptions.assumeTrue(available, "ffmpeg or libvips not available");
		FFmpeg.ProbeResult probe=FFmpeg.probe(clip("probe.mp4", 320, 240, 2, true));
		assertEquals(320, probe.width());
		assertEquals(240, probe.height());
		assertEquals(2.0, probe.duration(), 0.2);
		assertTrue(probe.hasAudio());
		assertEquals(25.0, probe.frameRate(), 0.01);
	}

	@Test
	void sniffRecognisesVideo() throws Exception{
		Assumptions.assumeTrue(available, "ffmpeg or libvips not available");
		assertEquals(MediaKind.VIDEO, MediaSniffer.sniff(clip("sniff.mp4", 320, 240, 1, false), "video/mp4"));
		// a video declared as an image is still recognised as a video
		assertEquals(MediaKind.VIDEO, MediaSniffer.sniff(clip("sniff2.mp4", 320, 240, 1, false), "application/octet-stream"));
	}

	@Test
	void smallVideoKeepsItsSizeAndGetsAPoster() throws Exception{
		Assumptions.assumeTrue(available, "ffmpeg or libvips not available");
		ProcessedMedia out=new VideoProcessor().process(clip("small.mp4", 640, 360, 2, true), ProcessingOptions.DEFAULT);
		try{
			assertEquals(MediaFileType.VIDEO, out.type());
			VideoMetadata meta=(VideoMetadata) out.metadata();
			assertEquals(640, meta.width());
			assertEquals(360, meta.height());
			assertEquals(2, meta.duration());
			assertNotNull(meta.blurhash());
			assertEquals(1, out.derived().size());
			ProcessedMedia poster=out.derived().getFirst();
			assertEquals(MediaFileType.IMAGE_PHOTO, poster.type());
			assertEquals(640, poster.metadata().width());
			assertEquals(360, poster.metadata().height());
			assertEquals(meta.blurhash(), poster.metadata().blurhash());

			FFmpeg.ProbeResult probe=FFmpeg.probe(out.file());
			assertEquals("h264", probe.videoCodec());
			assertEquals(640, probe.width());
			assertTrue(probe.hasAudio());
			assertTrue(out.file().length()>0);
		}finally{
			out.deleteFiles();
		}
	}

	@Test
	void largeVideoIsScaledTo720p() throws Exception{
		Assumptions.assumeTrue(available, "ffmpeg or libvips not available");
		ProcessedMedia out=new VideoProcessor().process(clip("large.mp4", 1920, 1080, 1, false), ProcessingOptions.DEFAULT);
		try{
			VideoMetadata meta=(VideoMetadata) out.metadata();
			assertEquals(1280, meta.width());
			assertEquals(720, meta.height());
			FFmpeg.ProbeResult probe=FFmpeg.probe(out.file());
			assertEquals(1280, probe.width());
			assertEquals(720, probe.height());
			assertFalse(probe.hasAudio());
		}finally{
			out.deleteFiles();
		}
	}

	@Test
	void targetSizeFitsPortraitAndOddSizes(){
		assertArrayEquals(new int[]{720, 1280}, VideoProcessor.targetSize(1080, 1920, 720));
		assertArrayEquals(new int[]{1280, 720}, VideoProcessor.targetSize(3840, 2160, 720));
		assertArrayEquals(new int[]{640, 480}, VideoProcessor.targetSize(640, 480, 720));
		int[] odd=VideoProcessor.targetSize(1001, 751, 720);
		assertEquals(0, odd[0]%2);
		assertEquals(0, odd[1]%2);
		assertTrue(odd[1]<=720);
	}

	@Test
	void posterTimeSkipsTheIntroButNotTooFar(){
		assertEquals(0.5, VideoProcessor.posterTime(5), 0.001);
		assertEquals(10, VideoProcessor.posterTime(600), 0.001);
	}
}
