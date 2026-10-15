package smithereen.model.objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import smithereen.Config;
import smithereen.Utils;
import smithereen.util.JsonArrayBuilder;
import smithereen.util.UriBuilder;

/**
 * Base class for JSON-serializable objects that are stored as part of other rows: profile fields
 * ({@link PropertyValue}) and the common part of actors (users, groups). Media attachments are
 * {@link smithereen.model.media.MediaFileRef}s and are not part of this hierarchy.
 * Objects are only ever parsed from JSON written by this server itself, never from the network.
 */
public abstract class StoredObject{
	protected static final Logger LOG=LoggerFactory.getLogger(StoredObject.class);

	public List<StoredObject> attachment;
	public String content;
	public @Nullable String name;
	public Instant published;
	public @Nullable String summary;
	public URI url;
	public String mediaType;

	/**
	 * Duration in milliseconds.
	 */
	public long duration;

	/**
	 * Canonical local URI of this object (for actors and other addressable objects), null for attachments.
	 */
	public URI activityPubID;

	public URI getActivityPubID(){
		return activityPubID;
	}

	public abstract String getType();

	public JsonObject toJson(JsonObject obj){
		if(obj==null)
			obj=new JsonObject();

		obj.addProperty("type", getType());
		if(activityPubID!=null)
			obj.addProperty("id", activityPubID.toString());
		if(attachment!=null && !attachment.isEmpty())
			obj.add("attachment", serializeObjectArray(attachment));
		if(content!=null)
			obj.addProperty("content", content);
		if(name!=null)
			obj.addProperty("name", name);
		if(published!=null)
			obj.addProperty("published", Utils.formatDateAsISO(published));
		if(summary!=null)
			obj.addProperty("summary", summary);
		if(url!=null)
			obj.addProperty("url", url.toString());
		if(mediaType!=null)
			obj.addProperty("mediaType", mediaType);
		if(duration!=0)
			obj.addProperty("duration", serializeDuration(duration));

		return obj;
	}

	public static <T extends StoredObject> List<T> parseSingleObjectOrArray(JsonElement o){
		if(o==null)
			return null;
		try{
			if(o.isJsonObject()){
				T item=(T)parse(o.getAsJsonObject());
				if(item==null)
					return null;
				return (List<T>) Collections.singletonList(item);
			}else if(o.isJsonArray()){
				ArrayList<T> res=new ArrayList<>();
				for(JsonElement el:o.getAsJsonArray()){
					T item=(T) parse(el.getAsJsonObject());
					if(item!=null)
						res.add(item);
				}
				return res.isEmpty() ? null : res;
			}
		}catch(ClassCastException ignore){}
		return null;
	}

	protected static URI tryParseURL(String url){
		if(url==null || url.isEmpty())
			return null;
		try{
			URI uri=UriBuilder.parseAndEncode(url);
			if("https".equals(uri.getScheme())){
				return uri;
			}else if("http".equals(uri.getScheme())){
				if(Config.useHTTP)
					return uri;
				return new UriBuilder(uri).scheme("https").build();
			}
			return null;
		}catch(URISyntaxException x){
			return null;
		}
	}

	protected Instant tryParseDate(String date){
		if(date==null)
			return null;
		return Utils.parseISODate(date);
	}

	protected long tryParseDuration(String duration){
		if(duration==null)
			return 0;
		long result=0;
		Pattern durationPattern=Pattern.compile("^(-)?P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$");
		Matcher matcher=durationPattern.matcher(duration);
		if(matcher.find()){
			int years=Utils.parseIntOrDefault(matcher.group(2), 0);
			int months=Utils.parseIntOrDefault(matcher.group(3), 0);
			int days=Utils.parseIntOrDefault(matcher.group(4), 0);
			int hours=Utils.parseIntOrDefault(matcher.group(5), 0);
			int minutes=Utils.parseIntOrDefault(matcher.group(6), 0);
			int seconds=Utils.parseIntOrDefault(matcher.group(7), 0);
			if(years>0 || months>0){
				LOG.warn("Trying to parse duration {} with years and/or months", duration);
				return 0;
			}
			result=seconds*1000L
					+minutes*60L*1000L
					+hours*60L*60L*1000L
					+days*24L*60L*60L*1000L;
		}

		return result;
	}

	public static JsonArray serializeObjectArray(List<? extends StoredObject> ar){
		JsonArray res=new JsonArray();
		for(StoredObject obj:ar){
			res.add(obj.toJson(new JsonObject()));
		}
		return res;
	}

	public static JsonElement serializeObjectArrayCompact(List<? extends StoredObject> ar){
		return ar.size()==1 ? ar.get(0).toJson(null) : serializeObjectArray(ar);
	}

	protected String serializeDuration(long d){
		StringBuilder sb=new StringBuilder(d>0 ? "P" : "-P");
		d=Math.abs(d/1000L);
		if(d>24L*60L*60L){
			long days=d/(24L*60L*60L);
			sb.append(days);
			sb.append('D');
			d-=days*24L*60L*60L;
		}
		if(d>0){
			sb.append('T');
			if(d>60L*60L){
				long hours=d/(60L*60L);
				sb.append(hours);
				sb.append('H');
				d-=hours*60L*60L;
			}
			if(d>60L){
				long minutes=d/60L;
				sb.append(minutes);
				sb.append('M');
				d-=minutes*60L;
			}
			if(d>0){
				sb.append(d);
				sb.append('S');
			}
		}
		return sb.toString();
	}

	protected static @Nullable String optString(@NotNull JsonObject obj, @NotNull String key){
		return switch(obj.get(key)){
			case JsonPrimitive primitive -> primitive.getAsString();
			case JsonObject jobj when jobj.get("@value") instanceof JsonPrimitive primitive -> primitive.getAsString();
			case null, default -> null;
		};
	}

	protected int optInt(JsonObject obj, String key){
		if(obj.has(key) && obj.get(key).isJsonPrimitive() && obj.getAsJsonPrimitive(key).isNumber())
			return obj.get(key).getAsInt();
		return -1;
	}

	protected long optLong(JsonObject obj, String key){
		if(obj.has(key) && obj.get(key).isJsonPrimitive() && obj.getAsJsonPrimitive(key).isNumber())
			return obj.get(key).getAsLong();
		return -1;
	}

	protected boolean optBoolean(JsonObject obj, String key){
		if(obj.has(key) && obj.get(key).isJsonPrimitive() && obj.getAsJsonPrimitive(key).isBoolean())
			return obj.get(key).getAsBoolean();
		return false;
	}

	protected JsonObject optObject(JsonObject obj, String key){
		if(obj.has(key) && obj.get(key).isJsonObject())
			return obj.getAsJsonObject(key);
		return null;
	}

	protected JsonArray optArray(JsonObject obj, String key){
		if(obj.has(key) && obj.get(key).isJsonArray())
			return obj.getAsJsonArray(key);
		return null;
	}

	public static JsonArray optArrayCompact(JsonObject obj, String key){
		if(!obj.has(key))
			return null;
		JsonElement el=obj.get(key);
		if(el.isJsonArray())
			return el.getAsJsonArray();
		return new JsonArrayBuilder().add(el).build();
	}

	protected StoredObject parseJson(JsonObject obj){
		activityPubID=tryParseURL(optString(obj, "id"));
		attachment=parseSingleObjectOrArray(obj.get("attachment"));
		content=optString(obj, "content");
		name=optString(obj, "name");
		published=tryParseDate(optString(obj, "published"));
		summary=optString(obj, "summary");
		url=tryParseURL(optString(obj, "url"));
		mediaType=optString(obj, "mediaType");
		duration=tryParseDuration(optString(obj, "duration"));
		return this;
	}

	@Override
	public String toString(){
		StringBuilder sb=new StringBuilder("StoredObject{");
		if(activityPubID!=null){
			sb.append("id=");
			sb.append(activityPubID);
		}
		if(attachment!=null){
			sb.append(", attachment=");
			sb.append(attachment);
		}
		if(content!=null){
			sb.append(", content='");
			sb.append(content);
			sb.append('\'');
		}
		if(name!=null){
			sb.append(", name='");
			sb.append(name);
			sb.append('\'');
		}
		if(published!=null){
			sb.append(", published=");
			sb.append(published);
		}
		if(summary!=null){
			sb.append(", summary='");
			sb.append(summary);
			sb.append('\'');
		}
		if(url!=null){
			sb.append(", url=");
			sb.append(url);
		}
		if(mediaType!=null){
			sb.append(", mediaType='");
			sb.append(mediaType);
			sb.append('\'');
		}
		if(duration!=0){
			sb.append(", duration=");
			sb.append(duration);
		}
		sb.append('}');
		return sb.toString();
	}

	/**
	 * Parse a stored object. Only the types this server writes itself are recognized.
	 */
	public static StoredObject parse(JsonObject obj){
		if(obj==null)
			return null;
		String type=Objects.requireNonNull(obj.get("type"), "type must not be null").getAsString();
		StoredObject res=switch(type){
			case "PropertyValue" -> new PropertyValue();
			default -> {
				LOG.debug("Unknown object type {}", type);
				yield null;
			}
		};
		if(res!=null)
			return res.parseJson(obj);
		return null;
	}
}
