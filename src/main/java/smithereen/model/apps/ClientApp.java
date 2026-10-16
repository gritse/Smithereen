package smithereen.model.apps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import smithereen.Config;
import smithereen.model.objects.StoredObject;
import smithereen.model.media.MediaFileRef;
import smithereen.model.SizedImage;
import smithereen.util.JsonArrayBuilder;
import smithereen.util.JsonObjectBuilder;
import spark.utils.StringUtils;

public class ClientApp{
	public long id;
	public String username;
	public String name;
	public ClientAppType type;
	public String description;
	public MediaFileRef logo;
	public int developerID;
	public Set<String> allowedRedirectURIs=Set.of();

	public static ClientApp fromResultSet(ResultSet res) throws SQLException{
		ClientApp a=new ClientApp();
		a.id=res.getLong("id");
		a.username=res.getString("username");
		a.type=ClientAppType.values()[res.getInt("type")];
		a.name=res.getString("name");
		a.description=res.getString("description");

		String _ava=res.getString("logo");
		if(_ava!=null){
			if(_ava.startsWith("{")){
				try{
					a.logo=MediaFileRef.parse(JsonParser.parseString(_ava).getAsJsonObject());
				}catch(Exception ignore){}
			}
		}

		a.developerID=res.getInt("developer_id");

		String extra=res.getString("extra");
		if(extra!=null){
			JsonObject e=JsonParser.parseString(extra).getAsJsonObject();
			a.allowedRedirectURIs=e.get("redirectURIs").getAsJsonArray().asList().stream().map(JsonElement::getAsString).collect(Collectors.toCollection(LinkedHashSet::new));
		}

		return a;
	}

	public String serializeExtraFields(){
		return new JsonObjectBuilder()
				.add("redirectURIs", allowedRedirectURIs.stream().collect(JsonArrayBuilder.COLLECTOR))
				.build()
				.toString();
	}

	public String getURL(){
		if(StringUtils.isNotEmpty(username)){
			return "/"+username;
		}
		return "/app"+id;
	}

	public SizedImage getLogo(){
		return logo==null ? null : logo.image();
	}

	public URI getActivityPubID(){
		return Config.localURI("/apps/"+id);
	}
}
