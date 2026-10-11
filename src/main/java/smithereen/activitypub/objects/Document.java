package smithereen.activitypub.objects;

import com.google.gson.JsonObject;

import org.jetbrains.annotations.Nullable;

import smithereen.model.BlurHashable;
import spark.utils.StringUtils;

public class Document extends ActivityPubObject implements BlurHashable{

	@Nullable
	private String blurHash;

	public int width;
	public int height;

	@Override
	public String getType(){
		return "Document";
	}

	@Override
	protected ActivityPubObject parseActivityPubObject(JsonObject obj){
		super.parseActivityPubObject(obj);
		setBlurHash(optString(obj, "blurhash"));
		width=optInt(obj, "width");
		height=optInt(obj, "height");
		return this;
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
	public JsonObject asActivityPubObject(JsonObject obj){
		obj=super.asActivityPubObject(obj);
		String blurHash=getBlurHash();
		if(StringUtils.isNotEmpty(blurHash)){
			obj.addProperty("blurhash", blurHash);
		}
		if(width>0)
			obj.addProperty("width", width);
		if(height>0)
			obj.addProperty("height", height);
		return obj;
	}
}
