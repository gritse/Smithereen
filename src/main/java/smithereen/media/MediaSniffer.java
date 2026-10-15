package smithereen.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import smithereen.libvips.VipsImage;
import smithereen.model.media.MediaKind;

/**
 * Looks at an uploaded file and says what kind of media it is, from its contents rather than its declared type.
 */
public final class MediaSniffer{
	private static final Logger LOG=LoggerFactory.getLogger(MediaSniffer.class);

	private MediaSniffer(){}

	/**
	 * @param declaredMimeType the Content-Type the client sent, used only as a hint for which decoder to try first
	 * @throws MediaValidationException if the file is not a kind of media this server accepts
	 */
	public static MediaKind sniff(File file, String declaredMimeType) throws MediaValidationException{
		boolean declaredVideo=declaredMimeType!=null && declaredMimeType.startsWith("video/");
		boolean declaredImage=declaredMimeType!=null && declaredMimeType.startsWith("image/");
		// The declared type only decides which decoder goes first; a video named .jpg is still a video
		if(!declaredVideo){
			MediaKind kind=sniffImage(file);
			if(kind!=null)
				return kind;
		}
		if(FFmpeg.isAvailable()){
			try{
				FFmpeg.ProbeResult probe=FFmpeg.probe(file);
				if(declaredVideo && probe.isImageFormat()){
					// A GIF or a still declared as video: an image if libvips reads it, else a one-frame video is not worth having
					MediaKind kind=sniffImage(file);
					if(kind!=null)
						return kind;
					throw MediaValidationException.unreadable();
				}
				return MediaKind.VIDEO;
			}catch(MediaValidationException x){
				if(declaredVideo){
					MediaKind kind=sniffImage(file);
					if(kind!=null)
						return kind;
				}
				// not a video either: fall through to the message that fits the declared type
			}catch(IOException x){
				LOG.warn("ffprobe failed", x);
				throw MediaValidationException.unreadable();
			}
		}else if(declaredVideo){
			throw new MediaValidationException(415, "err_file_upload_video_unavailable");
		}
		throw declaredImage || declaredVideo ? MediaValidationException.unreadable() : MediaValidationException.unsupportedFormat();
	}

	/**
	 * @return the image kind, or null if libvips can't read the file
	 */
	private static MediaKind sniffImage(File file){
		VipsImage img;
		try{
			img=new VipsImage(file.getAbsolutePath());
		}catch(IOException x){
			LOG.debug("Not an image that libvips can read: {}", x.getMessage());
			return null;
		}
		try{
			return img.isAnimated() ? MediaKind.ANIMATED_IMAGE : MediaKind.STILL_IMAGE;
		}finally{
			img.release();
		}
	}
}
