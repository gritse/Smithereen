package smithereen.model.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import smithereen.Utils;
import smithereen.model.ObfuscatedObjectIDType;
import smithereen.model.SizedImage;
import smithereen.model.photos.AvatarCropRects;
import smithereen.util.JsonObjectBuilder;
import smithereen.util.XTEA;
import spark.utils.StringUtils;

/**
 * A reference to a stored media file, as kept in the JSON columns of posts, comments, messages, avatars and app logos.
 * It carries the per-use presentation choices (alt text, rotation, avatar crop) and, once resolved through
 * {@link smithereen.storage.MediaFileResolver}, the file's record and a {@link StoredMedia} object that knows how to
 * render that kind of file.
 */
public final class MediaFileRef{
	private static final Logger LOG=LoggerFactory.getLogger(MediaFileRef.class);
	/**
	 * The "type" value in the stored JSON. Historical, kept so that existing rows parse unchanged.
	 */
	public static final String JSON_TYPE="_LocalImage";

	public long fileID;
	/**
	 * For attachments that are album photos and for avatars, the ID of the photo. 0 otherwise.
	 */
	public long photoID;
	public SizedImage.Rotation rotation;
	/**
	 * For avatars: the rectangle shown on the profile and the square thumbnail inside it.
	 */
	public AvatarCropRects avaCropRects;
	/**
	 * Legacy avatar crop (fractions of the full image), from before {@link #avaCropRects} existed.
	 */
	public float[] cropRegion;
	/**
	 * Alt text.
	 */
	@Nullable
	public String name;

	/**
	 * Set when resolved.
	 */
	public MediaFileRecord fileRecord;
	private StoredMedia media;

	public MediaFileRef(){}

	public MediaFileRef(long fileID){
		this.fileID=fileID;
	}

	public static MediaFileRef parse(JsonObject obj){
		String type=obj.has("type") ? obj.get("type").getAsString() : null;
		if(!JSON_TYPE.equals(type)){
			LOG.debug("Skipping stored object of type {}", type);
			return null;
		}
		MediaFileRef ref=new MediaFileRef();
		if(obj.has("_fileID"))
			ref.fileID=obj.get("_fileID").getAsLong();
		if(obj.has("_photoID"))
			ref.photoID=obj.get("_photoID").getAsLong();
		if(obj.has("_rot"))
			ref.rotation=SizedImage.Rotation.valueOf(obj.get("_rot").getAsInt());
		if(obj.has("_crop")){
			ref.avaCropRects=Utils.gson.fromJson(obj.get("_crop"), AvatarCropRects.class);
			ref.cropRegion=new float[]{ref.avaCropRects.thumb().x1(), ref.avaCropRects.thumb().y1(), ref.avaCropRects.thumb().x2(), ref.avaCropRects.thumb().y2()};
		}else if(obj.has("cropRegion") && obj.get("cropRegion").isJsonArray()){
			JsonArray cr=obj.getAsJsonArray("cropRegion");
			if(cr.size()==4){
				ref.cropRegion=new float[4];
				for(int i=0;i<4;i++)
					ref.cropRegion[i]=cr.get(i).getAsFloat();
			}
		}
		if(obj.has("name") && obj.get("name").isJsonPrimitive())
			ref.name=obj.get("name").getAsString();
		return ref;
	}

	/**
	 * Parses a stored attachment list, which is either one object or an array of them.
	 * @return the references, or null if there are none
	 */
	@Nullable
	public static List<MediaFileRef> parseList(JsonElement el){
		if(el==null)
			return null;
		ArrayList<MediaFileRef> result=new ArrayList<>();
		try{
			if(el.isJsonObject()){
				MediaFileRef ref=parse(el.getAsJsonObject());
				if(ref!=null)
					result.add(ref);
			}else if(el.isJsonArray()){
				for(JsonElement item:el.getAsJsonArray()){
					MediaFileRef ref=parse(item.getAsJsonObject());
					if(ref!=null)
						result.add(ref);
				}
			}
		}catch(RuntimeException x){
			LOG.warn("Failed to parse stored attachments {}", el, x);
		}
		return result.isEmpty() ? null : result;
	}

	public JsonObject toJson(){
		JsonObjectBuilder jb=new JsonObjectBuilder()
				.add("type", JSON_TYPE)
				.add("_fileID", fileID);
		if(photoID!=0)
			jb.add("_photoID", photoID);
		if(rotation!=null && rotation!=SizedImage.Rotation._0)
			jb.add("_rot", rotation.value());
		if(avaCropRects!=null)
			jb.add("_crop", Utils.gson.toJsonTree(avaCropRects));
		if(StringUtils.isNotEmpty(name))
			jb.add("name", name);
		return jb.build();
	}

	/**
	 * Serializes a list the way it is stored: one object for a single reference, an array otherwise.
	 */
	public static JsonElement serializeListCompact(@NotNull List<MediaFileRef> refs){
		if(refs.size()==1)
			return refs.getFirst().toJson();
		JsonArray arr=new JsonArray();
		for(MediaFileRef ref:refs)
			arr.add(ref.toJson());
		return arr;
	}

	public static String serializeList(@Nullable List<MediaFileRef> refs){
		if(refs==null || refs.isEmpty())
			return null;
		return serializeListCompact(refs).toString();
	}

	public boolean isResolved(){
		return fileRecord!=null;
	}

	/**
	 * Attaches the file's record and builds the media object for its kind.
	 */
	public void fillIn(@NotNull MediaFileRecord record){
		fileRecord=record;
		if(cropRegion==null)
			cropRegion=record.metadata().cropRegion();
		media=StoredMedia.create(this, record);
	}

	/**
	 * @return the resolved media. Before resolution, a placeholder still image that renders as a broken picture.
	 */
	@NotNull
	public StoredMedia media(){
		if(media==null)
			return new StillImage(this, null);
		return media;
	}

	/**
	 * @return the media as an image, or null if the file is not an image
	 */
	@Nullable
	public SizedImage image(){
		return media() instanceof SizedImage img ? img : null;
	}

	/**
	 * @return the image that stands for this file in thumbnails: the image itself, or a video's poster
	 */
	@Nullable
	public SizedImage previewImage(){
		return switch(media()){
			case SizedImage img -> img;
			case Video video -> video.getPoster();
		};
	}

	public MediaKind kind(){
		return fileRecord!=null ? fileRecord.id().type().kind() : MediaKind.STILL_IMAGE;
	}

	public boolean isGraffiti(){
		return fileRecord!=null && fileRecord.id().type()==MediaFileType.IMAGE_GRAFFITI;
	}

	/**
	 * @return the stored width of the file (before rotation), or 0 if unresolved
	 */
	public int getWidth(){
		return fileRecord!=null ? fileRecord.metadata().width() : 0;
	}

	public int getHeight(){
		return fileRecord!=null ? fileRecord.metadata().height() : 0;
	}

	/**
	 * The client-side ID of an attachment: the obfuscated file ID plus the upload token for the purpose.
	 * Album photos are referred to by their photo ID instead.
	 */
	public String getLocalID(MediaFileUploadPurpose purpose, int ownerID){
		if(fileRecord==null){
			LOG.warn("Tried to get a local ID for an unresolved media file reference (file ID {})", fileID);
			return null;
		}
		if(photoID!=0)
			return "photo:"+XTEA.encodeObjectID(photoID, ObfuscatedObjectIDType.PHOTO);
		return fileRecord.id().getEncodedID()+":"+MediaFileUploadTokens.getToken(fileRecord.id(), purpose, ownerID);
	}

	public String getLocalID(String purpose, int ownerID){
		return getLocalID(MediaFileUploadPurpose.valueOf(purpose), ownerID);
	}

	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof MediaFileRef that)) return false;
		return fileID==that.fileID && photoID==that.photoID;
	}

	@Override
	public int hashCode(){
		return Objects.hash(fileID, photoID);
	}

	@Override
	public String toString(){
		return "MediaFileRef{fileID="+fileID+(photoID!=0 ? ", photoID="+photoID : "")+"}";
	}
}
