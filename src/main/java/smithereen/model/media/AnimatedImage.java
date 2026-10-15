package smithereen.model.media;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

import smithereen.Config;

/**
 * An animated image, stored as animated WebP. imgproxy would keep only the first frame, so every size and format
 * is the stored file itself and the browser scales it. Rotation and cropping are not supported.
 */
public final class AnimatedImage extends StoredImage{
	public AnimatedImage(@NotNull MediaFileRef ref, @Nullable MediaFileRecord record){
		super(ref, record);
	}

	@Override
	@NotNull
	public MediaKind kind(){
		return MediaKind.ANIMATED_IMAGE;
	}

	@Override
	@NotNull
	public Dimensions getOriginalDimensions(){
		if(record==null)
			return Dimensions.UNKNOWN;
		return new Dimensions(record.metadata().width(), record.metadata().height());
	}

	@Override
	@Nullable
	@Contract("_, _, _, true, _ -> !null")
	public URI getUriForSizeAndFormat(@NotNull Type size, @NotNull Format format, boolean is2x, boolean useFallback, boolean addApiHash){
		if(record==null){
			LOG.warn("Tried to get a URL for an unresolved media file reference (file ID {})", ref.fileID);
			if(useFallback)
				return Config.localURI(size==Type.AVA_SQUARE_SMALL || (is2x && size==Type.AVA_SQUARE_MEDIUM) ? "/res/broken_photo_small.svg" : "/res/broken_photo.svg");
			return null;
		}
		return getFileURL();
	}
}
