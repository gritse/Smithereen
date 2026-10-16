package smithereen.media;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import smithereen.model.media.AudioMetadata;
import smithereen.model.media.MediaFileType;
import smithereen.model.media.MediaKind;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the audio pipeline on tones that ffmpeg generates itself. Skipped when ffmpeg is not available.
 */
public class AudioProcessorTest{
	private static boolean available;

	@TempDir
	Path tmp;

	@BeforeAll
	static void checkTools(){
		available=FFmpeg.isAvailable();
	}

	private File tone(String name, int seconds, String codec, String title) throws IOException{
		File f=tmp.resolve(name).toFile();
		List<String> cmd=List.of("ffmpeg", "-nostdin", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration="+seconds,
				"-metadata", "title="+title, "-metadata", "artist=Test Artist", "-c:a", codec, f.getAbsolutePath());
		FFmpeg.run(cmd, 120);
		return f;
	}

	@Test
	void probeReadsDurationChannelsAndTags() throws Exception{
		Assumptions.assumeTrue(available, "ffmpeg not available");
		FFmpeg.ProbeResult probe=FFmpeg.probe(tone("tags.mp3", 3, "libmp3lame", "A Song"));
		assertFalse(probe.hasVideo());
		assertTrue(probe.hasAudio());
		assertEquals(3.0, probe.duration(), 0.2);
		assertEquals(1, probe.audioChannels());
		assertEquals("A Song", probe.title());
		assertEquals("Test Artist", probe.artist());
	}

	@Test
	void sniffRecognisesAudio() throws Exception{
		Assumptions.assumeTrue(available, "ffmpeg not available");
		assertEquals(MediaKind.AUDIO, MediaSniffer.sniff(tone("sniff.wav", 1, "pcm_s16le", "x"), "audio/wav"));
		// declared as something else, it's still audio
		assertEquals(MediaKind.AUDIO, MediaSniffer.sniff(tone("sniff2.wav", 1, "pcm_s16le", "x"), "application/octet-stream"));
	}

	@Test
	void wavBecomesAacWithItsTags() throws Exception{
		Assumptions.assumeTrue(available, "ffmpeg not available");
		File wav=tone("big.wav", 5, "pcm_s16le", "Lossless Thing");
		ProcessedMedia out=new AudioProcessor().process(wav, ProcessingOptions.DEFAULT);
		try{
			assertEquals(MediaFileType.AUDIO, out.type());
			AudioMetadata meta=(AudioMetadata) out.metadata();
			assertEquals(5, meta.duration());
			assertEquals("Lossless Thing", meta.title());
			assertEquals("Test Artist", meta.artist());
			FFmpeg.ProbeResult probe=FFmpeg.probe(out.file());
			assertFalse(probe.hasVideo());
			assertTrue(probe.hasAudio());
			assertEquals(5.0, probe.duration(), 0.3);
			assertTrue(out.file().length()<wav.length()/3, "AAC should be much smaller than PCM: "+out.file().length()+" vs "+wav.length());
			assertNull(probe.title(), "the transcode strips the tags from the file itself; they live in the metadata");
		}finally{
			out.deleteFiles();
		}
	}

	@Test
	void durationIsFormattedForDisplay(){
		assertEquals("0:05", smithereen.templates.AudioRenderer.formatDuration(5));
		assertEquals("3:07", smithereen.templates.AudioRenderer.formatDuration(187));
		assertEquals("1:00:00", smithereen.templates.AudioRenderer.formatDuration(3600));
	}
}
