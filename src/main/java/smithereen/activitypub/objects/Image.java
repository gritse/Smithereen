package smithereen.activitypub.objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class Image extends Document{

	public float[] cropRegion;
	public boolean isGraffiti;

	@Override
	public String getType(){
		return "Image";
	}

	@Override
	public JsonObject asActivityPubObject(JsonObject obj){
		obj=super.asActivityPubObject(obj);
		if(cropRegion!=null){
			JsonArray _cr=new JsonArray();
			for(float f:cropRegion) _cr.add((double)f);
			obj.add("cropRegion", _cr);
		}
		if(isGraffiti){
			obj.addProperty("graffiti", true);
		}
		return obj;
	}

	@Override
	protected ActivityPubObject parseActivityPubObject(JsonObject obj){
		super.parseActivityPubObject(obj);
		JsonArray _cr=optArray(obj, "cropRegion");
		if(_cr!=null && _cr.size()==4){
			cropRegion=new float[4];
			for(int i=0;i<4;i++)
				cropRegion[i]=_cr.get(i).getAsFloat();
		}
		isGraffiti=optBoolean(obj, "graffiti");
		return this;
	}
}
