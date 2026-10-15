package smithereen.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import smithereen.libvips.VipsImage;
import smithereen.model.media.ImageMetadata;
import smithereen.model.media.MediaKind;
import smithereen.util.BlurHash;

/**
 * Photos, avatars, logos, graffiti: the first (only) frame, flattened onto white, scaled down to the bound, written as WebP.
 */
public final class StillImageProcessor implements MediaProcessor{
	private static final Logger LOG=LoggerFactory.getLogger(StillImageProcessor.class);

	@Override
	public MediaKind kind(){
		return MediaKind.STILL_IMAGE;
	}

	@Override
	public ProcessedMedia process(File input, ProcessingOptions options) throws IOException, MediaValidationException{
		VipsImage img=load(input);
		File out=null;
		try{
			validate(img, options);
			out=File.createTempFile("SmithereenUploadResized", ".webp");
			int[] outSize={0, 0};
			ImageEncoder.writeStill(img, options.maxSize(), options.lossless(), out, outSize);
			ImageMetadata meta=new ImageMetadata(outSize[0], outSize[1], BlurHash.encode(img, 4, 4), null);
			ProcessedMedia result=new ProcessedMedia(out, options.stillImageType(), meta);
			out=null;
			return result;
		}finally{
			img.release();
			if(out!=null)
				out.delete();
		}
	}

	/**
	 * Loads the first frame of an image file and flattens transparency onto white.
	 */
	static VipsImage load(File input) throws MediaValidationException{
		VipsImage img;
		try{
			img=new VipsImage(input.getAbsolutePath());
		}catch(IOException x){
			LOG.warn("VipsImage error", x);
			throw MediaValidationException.unreadable();
		}
		try{
			return flatten(img);
		}catch(MediaValidationException x){
			img.release();
			throw x;
		}
	}

	/**
	 * @return the image flattened onto white, with the original released; or the original itself if it has no alpha.
	 * On failure the original is left for the caller to release.
	 */
	static VipsImage flatten(VipsImage img) throws MediaValidationException{
		if(!img.hasAlpha())
			return img;
		try{
			VipsImage flat=img.flatten(255, 255, 255);
			img.release();
			return flat;
		}catch(IOException x){
			LOG.warn("VipsImage error", x);
			throw MediaValidationException.unreadable();
		}
	}

	static void validate(VipsImage img, ProcessingOptions options) throws MediaValidationException{
		int w=img.getWidth(), h=img.getFrameHeight();
		if((options.exactWidth()!=0 && w!=options.exactWidth()) || (options.exactHeight()!=0 && h!=options.exactHeight())){
			LOG.warn("Unexpected image size {}x{}, expected {}x{}", w, h, options.exactWidth(), options.exactHeight());
			throw new MediaValidationException(400, "err_file_upload_image_format");
		}
		if(options.requireSquare() && w!=h)
			throw new MediaValidationException(422, "err_app_logo_not_square");
		if(options.minSize()!=0 && Math.min(w, h)<options.minSize())
			throw new MediaValidationException(422, "err_app_logo_too_small", Map.of("size", options.minSize()));
	}
}
