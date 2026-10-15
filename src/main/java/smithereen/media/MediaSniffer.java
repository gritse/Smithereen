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
		if(declaredMimeType==null || declaredMimeType.startsWith("image/")){
			VipsImage img;
			try{
				img=new VipsImage(file.getAbsolutePath());
			}catch(IOException x){
				LOG.debug("Not an image that libvips can read: {}", x.getMessage());
				throw declaredMimeType==null ? MediaValidationException.unsupportedFormat() : MediaValidationException.unreadable();
			}
			try{
				return img.isAnimated() ? MediaKind.ANIMATED_IMAGE : MediaKind.STILL_IMAGE;
			}finally{
				img.release();
			}
		}
		throw MediaValidationException.unsupportedFormat();
	}
}
