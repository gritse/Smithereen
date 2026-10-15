package smithereen.model.objects;

import com.google.gson.JsonObject;

import smithereen.text.TextProcessor;

public class PropertyValue extends StoredObject{

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
	protected StoredObject parseJson(JsonObject obj){
		super.parseJson(obj);
		if(obj.has("value"))
			value=TextProcessor.sanitizeHTML(obj.get("value").getAsString());
		else
			value="";
		return this;
	}

	@Override
	public JsonObject toJson(JsonObject obj){
		obj=super.toJson(obj);
		obj.addProperty("value", value);
		return obj;
	}
}
