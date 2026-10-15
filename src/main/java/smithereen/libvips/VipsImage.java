package smithereen.libvips;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static smithereen.libvips.LibVips.*;

public class VipsImage{
	private static final List<String> LOADER_WHITELIST=List.of(
			"VipsForeignLoadJpegFile",
//			"VipsForeignLoadPng",
			"VipsForeignLoadPngFile",
			"VipsForeignLoadGifFile",
			"VipsForeignLoadWebpFile",
			"VipsForeignLoadHeifFile",
			"VipsForeignLoadNsgifFile"
	);
	/**
	 * Loaders that can contain more than one frame and accept the "n" option to load all of them.
	 */
	private static final List<String> ANIMATED_LOADERS=List.of(
			"VipsForeignLoadGifFile",
			"VipsForeignLoadNsgifFile",
			"VipsForeignLoadWebpFile"
	);
	public static final int MAX_SIZE=10_000;

	Pointer nativePtr;
	private boolean released;
	/**
	 * Number of frames in the source file, only counted for formats in {@link #ANIMATED_LOADERS}.
	 */
	private int numFrames=1;

	private static final Logger LOG=LoggerFactory.getLogger(VipsImage.class);

	/**
	 * Loads the first frame of an image. For animated images, {@link #isAnimated()} still reports
	 * whether the file has more frames.
	 */
	public VipsImage(String filePath) throws IOException{
		this(filePath, false);
	}

	/**
	 * @param allFrames if true and the file is an animated GIF or WebP, load all of its frames. libvips then represents
	 *                  the animation as one tall image with the frames stacked vertically, each {@link #getFrameHeight()} pixels high.
	 *                  Such an image is read sequentially, so it can only be processed and written once.
	 */
	public VipsImage(String filePath, boolean allFrames) throws IOException{
		String loader=vips_foreign_find_load(filePath);
		if(loader==null)
			throw new IOException("File format not supported");
		if(!LOADER_WHITELIST.contains(loader)){
			LOG.warn("libvips loader not allowed: {}", loader);
			throw new IOException("File format not supported");
		}
		ArrayList<String> options=new ArrayList<>();
		if(loader.equals("VipsForeignLoadJpegFile")){
			options.add("autorotate=true");
		}
		if(allFrames && ANIMATED_LOADERS.contains(loader)){
			options.add("n=-1");
			options.add("access=sequential");
		}
		if(!options.isEmpty())
			filePath+="["+String.join(",", options)+"]";
		nativePtr=vips_image_new_from_file(filePath);
		if(nativePtr==Pointer.NULL){
			throwError();
		}

		if(ANIMATED_LOADERS.contains(loader))
			numFrames=Math.max(1, vips_image_get_n_pages(nativePtr));
		int width=getWidth();
		int height=getFrameHeight();
		if(width>MAX_SIZE || height>MAX_SIZE){
			release();
			throw new IOException("Image size "+width+"x"+height+" exceeds the limit of "+MAX_SIZE+" on largest side");
		}
	}

	private VipsImage(Pointer ptr, int numFrames){
		nativePtr=ptr;
		this.numFrames=numFrames;
	}

	public int getWidth(){
		ensureNotReleased();
		return vips_image_get_width(nativePtr);
	}

	public int getHeight(){
		ensureNotReleased();
		return vips_image_get_height(nativePtr);
	}

	/**
	 * @return the number of frames in the file this image was loaded from, regardless of how many of them were actually loaded.
	 * Always 1 for formats other than GIF and WebP, even if the file has several pages, like a multi-image HEIF.
	 */
	public int getNumFrames(){
		return numFrames;
	}

	/**
	 * @return true if the file this image was loaded from has more than one frame
	 */
	public boolean isAnimated(){
		return numFrames>1;
	}

	/**
	 * @return the height of one frame. Equal to {@link #getHeight()} unless this image contains several frames stacked vertically.
	 */
	public int getFrameHeight(){
		ensureNotReleased();
		return vips_image_get_page_height(nativePtr);
	}

	/**
	 * @return the number of frames stacked vertically in this image, 1 for a still image or when only the first frame was loaded
	 */
	public int getLoadedFrames(){
		return getHeight()/getFrameHeight();
	}

	/**
	 * Updates the frame height, needed after resizing an image that contains several frames so that writers
	 * know where each frame begins. The image height must be a multiple of the new frame height.
	 */
	public void setFrameHeight(int frameHeight){
		ensureNotReleased();
		if(frameHeight<=0 || getHeight()%frameHeight!=0)
			throw new IllegalArgumentException("Image height "+getHeight()+" is not a multiple of the frame height "+frameHeight);
		vips_image_set_int(nativePtr, "page-height", frameHeight);
	}

	public void release(){
		ensureNotReleased();
		LibGObject.g_object_unref(nativePtr);
		released=true;
	}

	public VipsImage resize(double scale) throws IOException{
		ensureNotReleased();
		PointerByReference out=new PointerByReference();
		if(vips_resize(nativePtr, out, scale)!=0){
			throwError();
		}
		return new VipsImage(out.getValue(), numFrames);
	}

	public VipsImage resize(double hscale, double vscale) throws IOException{
		ensureNotReleased();
		PointerByReference out=new PointerByReference();
		if(vips_resize(nativePtr, out, hscale, vscale)!=0){
			throwError();
		}
		return new VipsImage(out.getValue(), numFrames);
	}

	public VipsImage crop(int left, int top, int width, int height) throws IOException{
		ensureNotReleased();
		PointerByReference out=new PointerByReference();
		if(vips_crop(nativePtr, out, left, top, width, height)!=0){
			throwError();
		}
		return new VipsImage(out.getValue(), numFrames);
	}

	public void writeToFile(String fileName) throws IOException{
		ensureNotReleased();
		if(vips_image_write_to_file(nativePtr, fileName)!=0){
			throwError();
		}
	}

	public boolean hasAlpha(){
		ensureNotReleased();
		return vips_image_hasalpha(nativePtr);
	}

	public VipsImage flatten(double r, double g, double b) throws IOException{
		ensureNotReleased();
		Pointer arr=vips_array_double_new(new double[]{r, g, b}, 3);
		PointerByReference out=new PointerByReference();
		try{
			if(vips_flatten(nativePtr, out, arr)!=0){
				throwError();
			}
		}finally{
			vips_area_unref(arr);
		}
		return new VipsImage(out.getValue(), numFrames);
	}

	public int getBands(){
		ensureNotReleased();
		return vips_image_get_bands(nativePtr);
	}

	public BandFormat getFormat(){
		ensureNotReleased();
		return BandFormat.valueOf(vips_image_get_format(nativePtr));
	}

	public VipsImage castUChar() throws IOException{
		ensureNotReleased();
		PointerByReference out=new PointerByReference();
		if(vips_cast_uchar(nativePtr, out)!=0)
			throwError();
		return new VipsImage(out.getValue(), numFrames);
	}

	public List<String> getFields(){
		ensureNotReleased();
		Pointer strings=vips_image_get_fields(nativePtr);
		Pointer[] strarr=strings.getPointerArray(0);
		ArrayList<String> res=new ArrayList<>(strarr.length);
		for(Pointer ptr:strarr){
			res.add(ptr.getString(0));
		}
		LibGLib.g_strfreev(strings);
		return res;
	}

	public boolean removeField(String name){
		ensureNotReleased();
		return vips_image_remove(nativePtr, name);
	}

	public boolean hasField(String name){
		ensureNotReleased();
		return vips_image_get_typeof(nativePtr, name)!=Pointer.NULL;
	}

	public VipsImage iccTransform(String outputProfile) throws IOException{
		ensureNotReleased();
		PointerByReference out=new PointerByReference();
		if(vips_icc_transform(nativePtr, out, outputProfile)!=0)
			throwError();
		return new VipsImage(out.getValue(), numFrames);
	}

	public boolean hasColorProfile(){
		return hasField("icc-profile-data");
	}

	void ensureNotReleased(){
		if(released)
			throw new IllegalStateException("This VipsImage was released");
	}

	public enum BandFormat{
		NOTSET,
		UCHAR,
		CHAR,
		USHORT,
		SHORT,
		UINT,
		INT,
		FLOAT,
		COMPLEX,
		DOUBLE,
		DPCOMPLEX,
		LAST;

		private static BandFormat valueOf(int v){
			return switch(v){
				case -1 -> NOTSET;
				case 0 -> UCHAR;
				case 1 -> CHAR;
				case 2 -> USHORT;
				case 3 -> SHORT;
				case 4 -> UINT;
				case 5 -> INT;
				case 6 -> FLOAT;
				case 7 -> COMPLEX;
				case 8 -> DOUBLE;
				case 9 -> DPCOMPLEX;
				case 10 -> LAST;
				default -> throw new IllegalStateException("Unexpected value: "+v);
			};
		}
	}
}
