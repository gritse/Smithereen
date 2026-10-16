package smithereen.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import smithereen.Config;
import smithereen.model.media.AudioMetadata;
import smithereen.model.media.MediaFileType;
import smithereen.model.media.MediaKind;

/**
 * Audio: probed and checked during the upload, then transcoded to AAC in M4A on the processing queue.
 * There is nothing to resize, but the transcode still matters: a 60 MB WAV becomes a few MB, and the
 * server only ever serves one container.
 */
public final class AudioProcessor implements MediaProcessor{
	private static final Logger LOG=LoggerFactory.getLogger(AudioProcessor.class);

	/**
	 * @param durationSeconds rounded to whole seconds
	 * @param channels of the source; the transcode keeps mono and stereo and downmixes anything else
	 */
	public record Prepared(int durationSeconds, int channels, String title, String artist){
		public AudioMetadata metadata(){
			return new AudioMetadata(durationSeconds, title, artist);
		}
	}

	@Override
	public MediaKind kind(){
		return MediaKind.AUDIO;
	}

	@Override
	public ProcessedMedia process(File input, ProcessingOptions options) throws IOException, MediaValidationException{
		Prepared prepared=prepare(input);
		File out=transcode(input, prepared.channels());
		return new ProcessedMedia(out, MediaFileType.AUDIO, prepared.metadata());
	}

	public Prepared prepare(File input) throws IOException, MediaValidationException{
		if(!FFmpeg.isAvailable())
			throw new MediaValidationException(415, "err_file_upload_audio_unavailable");
		FFmpeg.ProbeResult probe=FFmpeg.probe(input);
		if(!probe.hasAudio())
			throw MediaValidationException.unreadable();
		if(probe.duration()>Config.audioMaxDuration)
			throw new MediaValidationException(422, "err_file_upload_audio_too_long", Map.of("maxDuration", Config.audioMaxDuration/60));
		return new Prepared((int) Math.round(probe.duration()), probe.audioChannels(), probe.title(), probe.artist());
	}

	/**
	 * Transcodes to a new temporary file, which the caller stores and deletes.
	 */
	public File transcode(File input, int sourceChannels) throws IOException{
		File out=File.createTempFile("SmithereenAudio", ".m4a");
		try{
			long start=System.currentTimeMillis();
			FFmpeg.transcodeAudio(input, out, sourceChannels);
			LOG.debug("Transcoded {} in {} ms", input, System.currentTimeMillis()-start);
			return out;
		}catch(IOException | RuntimeException x){
			out.delete();
			throw x;
		}
	}
}
