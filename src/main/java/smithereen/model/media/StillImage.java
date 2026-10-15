package smithereen.model.media;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

import smithereen.Config;
import smithereen.model.photos.AbsoluteImageRect;
import smithereen.storage.ImgProxy;
import smithereen.storage.media.MediaFileStorageDriver;

/**
 * A still image. Every size and format is a signed imgproxy URL that resizes, converts, rotates and crops the stored WebP.
 */
public final class StillImage extends StoredImage{
	public StillImage(@NotNull MediaFileRef ref, @Nullable MediaFileRecord record){
		super(ref, record);
	}

	@Override
	@NotNull
	public MediaKind kind(){
		return MediaKind.STILL_IMAGE;
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
		ImgProxy.UrlBuilder builder=MediaFileStorageDriver.getInstance().getImgProxyURL(record.id())
				.format(format)
				.resize(size.getResizingType(), size.getMaxWidth(), size.getMaxHeight(), false, false)
				.rotate(ref.rotation);
		int width=record.metadata().width(), height=record.metadata().height();
		if(ref.avaCropRects!=null){
			int w, h;
			if(isRotatedSideways()){
				w=height;
				h=width;
			}else{
				w=width;
				h=height;
			}
			if(size==Type.AVA_RECT || size==Type.AVA_RECT_LARGE){
				AbsoluteImageRect profileCrop=ref.avaCropRects.profile().makeAbsolute(w, h);
				builder.crop(profileCrop.x1(), profileCrop.y1(), profileCrop.getWidth(), profileCrop.getHeight());
			}else if(size==Type.AVA_SQUARE_SMALL || size==Type.AVA_SQUARE_MEDIUM || size==Type.AVA_SQUARE_LARGE || size==Type.AVA_SQUARE_XLARGE){
				AbsoluteImageRect profileCrop=ref.avaCropRects.profile().makeAbsolute(w, h);
				AbsoluteImageRect squareCrop=ref.avaCropRects.thumb().makeAbsolute(profileCrop.getWidth(), profileCrop.getHeight());
				int croppedSize=squareCrop.getWidth();
				builder.crop(profileCrop.x1()+squareCrop.x1(), profileCrop.y1()+squareCrop.y1(), croppedSize, croppedSize);
			}
		}else if(ref.cropRegion!=null && size.getResizingType()==ImgProxy.ResizingType.FILL){
			int x=Math.round(ref.cropRegion[0]*width);
			int y=Math.round(ref.cropRegion[1]*height);
			builder.crop(x, y, Math.round(ref.cropRegion[2]*width-x), Math.round(ref.cropRegion[3]*height-y));
		}
		return builder.build();
	}

	@Override
	public Dimensions getDimensionsForSize(Type size){
		Dimensions d;
		if(ref.avaCropRects!=null && record!=null){
			int w, h;
			if(isRotatedSideways()){
				w=record.metadata().height();
				h=record.metadata().width();
			}else{
				w=record.metadata().width();
				h=record.metadata().height();
			}
			if(size==Type.AVA_RECT || size==Type.AVA_RECT_LARGE){
				AbsoluteImageRect profileCrop=ref.avaCropRects.profile().makeAbsolute(w, h);
				d=new Dimensions(profileCrop.getWidth(), profileCrop.getHeight());
			}else if(size==Type.AVA_SQUARE_SMALL || size==Type.AVA_SQUARE_MEDIUM || size==Type.AVA_SQUARE_LARGE || size==Type.AVA_SQUARE_XLARGE){
				AbsoluteImageRect profileCrop=ref.avaCropRects.profile().makeAbsolute(w, h);
				AbsoluteImageRect squareCrop=ref.avaCropRects.thumb().makeAbsolute(profileCrop.getWidth(), profileCrop.getHeight());
				int croppedSize=squareCrop.getWidth();
				d=new Dimensions(croppedSize, croppedSize);
			}else{
				d=getOriginalDimensions();
			}
		}else{
			d=getOriginalDimensions();
		}
		return size.getResizedDimensions(d);
	}

	@Override
	public URI getOriginalURI(){
		if(record==null)
			return null;
		if(ref.rotation!=null){
			return MediaFileStorageDriver.getInstance().getImgProxyURL(record.id())
					.format(Format.WEBP)
					.rotate(ref.rotation)
					.build();
		}
		return getFileURL();
	}
}
