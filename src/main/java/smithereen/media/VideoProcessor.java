package smithereen.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import smithereen.Config;
import smithereen.libvips.VipsImage;
import smithereen.model.media.MediaFileType;
import smithereen.model.media.MediaKind;
import smithereen.model.media.VideoMetadata;

/**
 * Videos: probed and checked, a poster frame extracted and run through the still-image pipeline, then transcoded
 * to H.264 and AAC in MP4, scaled down so that the shorter side is at most {@link Config#videoMaxHeight}.
 * {@link #prepare} is quick and runs during the upload; {@link #transcode} is slow and runs on the processing queue.
 * {@link #process} does both, for callers that can wait.
 */
public final class VideoProcessor implements MediaProcessor{
	private static final Logger LOG=LoggerFactory.getLogger(VideoProcessor.class);

	/**
	 * Everything known about a video before transcoding it.
	 * @param width the width the transcoded video will have
	 * @param durationSeconds rounded to whole seconds
	 * @param poster the poster frame, already processed as a still image
	 */
	public record Prepared(int width, int height, int durationSeconds, double frameRate, ProcessedMedia poster){
		public String blurhash(){
			return poster.metadata().blurhash();
		}
	}

	@Override
	public MediaKind kind(){
		return MediaKind.VIDEO;
	}

	@Override
	public ProcessedMedia process(File input, ProcessingOptions options) throws IOException, MediaValidationException{
		Prepared prepared=prepare(input, options);
		try{
			File out=transcode(input, prepared);
			return new ProcessedMedia(out, MediaFileType.VIDEO, new VideoMetadata(prepared.width(), prepared.height(), prepared.durationSeconds(), 0, prepared.blurhash(), prepared.frameRate()), List.of(prepared.poster()));
		}catch(IOException | RuntimeException x){
			prepared.poster().deleteFiles();
			throw x;
		}
	}

	/**
	 * Probes the file, checks it against the limits and extracts the poster frame.
	 */
	public Prepared prepare(File input, ProcessingOptions options) throws IOException, MediaValidationException{
		if(!FFmpeg.isAvailable())
			throw new MediaValidationException(415, "err_file_upload_video_unavailable");
		FFmpeg.ProbeResult probe=FFmpeg.probe(input);
		if(!probe.hasVideo())
			throw MediaValidationException.unreadable();
		if(probe.duration()>Config.videoMaxDuration)
			throw new MediaValidationException(422, "err_file_upload_video_too_long", Map.of("maxDuration", Config.videoMaxDuration/60));
		if(probe.width()>VipsImage.MAX_SIZE || probe.height()>VipsImage.MAX_SIZE)
			throw MediaValidationException.unreadable();
		int[] size=targetSize(probe.width(), probe.height(), Config.videoMaxHeight);
		File posterPng=File.createTempFile("SmithereenPoster", ".png");
		ProcessedMedia poster;
		try{
			FFmpeg.extractPoster(input, posterTime(probe.duration()), size[0], size[1], posterPng);
			poster=new StillImageProcessor().process(posterPng, ProcessingOptions.DEFAULT);
		}finally{
			posterPng.delete();
		}
		return new Prepared(size[0], size[1], (int) Math.round(probe.duration()), probe.frameRate(), poster);
	}

	/**
	 * Transcodes the file to a new temporary file, which the caller stores and deletes.
	 */
	public File transcode(File input, Prepared prepared) throws IOException{
		return transcode(input, prepared.width(), prepared.height(), prepared.frameRate());
	}

	public File transcode(File input, int width, int height, double sourceFrameRate) throws IOException{
		File out=File.createTempFile("SmithereenVideo", ".mp4");
		try{
			long start=System.currentTimeMillis();
			FFmpeg.transcode(input, out, width, height, sourceFrameRate);
			LOG.debug("Transcoded {} to {}x{} in {} ms", input, width, height, System.currentTimeMillis()-start);
			return out;
		}catch(IOException | RuntimeException x){
			out.delete();
			throw x;
		}
	}

	/**
	 * Fits the video into a box whose shorter side is {@code maxShortSide} and longer side 16/9 of that, keeping the aspect ratio
	 * and rounding to even numbers as H.264 requires. Smaller videos keep their size.
	 */
	static int[] targetSize(int width, int height, int maxShortSide){
		int maxLongSide=maxShortSide*16/9;
		int longSide=Math.max(width, height), shortSide=Math.min(width, height);
		double factor=Math.min(1.0, Math.min((double) maxShortSide/shortSide, (double) maxLongSide/longSide));
		int w=(int) Math.round(width*factor), h=(int) Math.round(height*factor);
		w=Math.max(2, w-w%2);
		h=Math.max(2, h-h%2);
		return new int[]{w, h};
	}

	/**
	 * The poster is taken a tenth of the way in, but no later than ten seconds, so that intros and fades are skipped on long videos.
	 */
	static double posterTime(double duration){
		return Math.min(duration*0.1, 10);
	}
}
