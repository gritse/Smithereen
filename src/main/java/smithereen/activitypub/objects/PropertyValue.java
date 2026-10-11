package smithereen.activitypub.objects;

import com.google.gson.JsonObject;

import smithereen.text.TextProcessor;

public class PropertyValue extends ActivityPubObject{

	public String value;
	public boolean parsed;

	public PropertyValue(){}

	public PropertyValue(String name, String value){
		this.name=name;
		this.value=value;
	}

	@Override
	public String getType(){
		return "PropertyValue";
	}

	@Override
	protected ActivityPubObject parseActivityPubObject(JsonObject obj){
		super.parseActivityPubObject(obj);
		if(obj.has("value"))
			value=TextProcessor.sanitizeHTML(obj.get("value").getAsString());
		else
			value="";
		return this;
	}

	@Override
	public JsonObject asActivityPubObject(JsonObject obj){
		obj=super.asActivityPubObject(obj);
		obj.addProperty("value", value);
		return obj;
	}
}
