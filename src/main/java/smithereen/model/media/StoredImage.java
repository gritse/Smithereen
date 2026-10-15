package smithereen.model.media;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

import smithereen.model.SizedImage;

/**
 * Common part of the image kinds: dimensions, blurhash and the rotation chosen for the reference.
 */
public abstract sealed class StoredImage implements SizedImage permits StillImage, AnimatedImage{
	protected static final Logger LOG=LoggerFactory.getLogger(StoredImage.class);
	protected final MediaFileRef ref;
	@Nullable
	protected final MediaFileRecord record;
	private String blurHash;

	protected StoredImage(@NotNull MediaFileRef ref, @Nullable MediaFileRecord record){
		this.ref=ref;
		this.record=record;
		if(record!=null)
			blurHash=record.metadata().blurhash();
	}

	@Override
	@NotNull
	public MediaFileRef ref(){
		return ref;
	}

	@Override
	@Nullable
	public MediaFileRecord record(){
		return record;
	}

	public boolean isGraffiti(){
		return record!=null && record.id().type()==MediaFileType.IMAGE_GRAFFITI;
	}

	protected boolean isRotatedSideways(){
		return ref.rotation==Rotation._90 || ref.rotation==Rotation._270;
	}

	@Override
	@NotNull
	public Dimensions getOriginalDimensions(){
		if(record==null)
			return Dimensions.UNKNOWN;
		int w=record.metadata().width(), h=record.metadata().height();
		return isRotatedSideways() ? new Dimensions(h, w) : new Dimensions(w, h);
	}

	@Override
	public URI getOriginalURI(){
		return getFileURL();
	}

	@Override
	@Nullable
	public String getBlurHash(){
		return blurHash;
	}

	@Override
	public void setBlurHash(@Nullable String blurHash){
		this.blurHash=blurHash;
	}

	@Override
	public boolean isAnimated(){
		return kind()==MediaKind.ANIMATED_IMAGE;
	}
}
