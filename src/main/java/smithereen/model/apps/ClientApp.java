package smithereen.model.apps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import smithereen.Config;
import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.activitypub.objects.Image;
import smithereen.activitypub.objects.LocalImage;
import smithereen.model.SizedImage;
import smithereen.storage.DatabaseUtils;
import smithereen.util.JsonArrayBuilder;
import smithereen.util.JsonObjectBuilder;
import spark.utils.StringUtils;

public class ClientApp{
	public long id;
	public String username;
	public String name;
	public ClientAppType type;
	public String description;
	public Image logo;
	public int developerID;
	public Instant lastUpdated;
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
					a.logo=(Image) ActivityPubObject.parse(JsonParser.parseString(_ava).getAsJsonObject());
				}catch(Exception ignore){}
			}
		}

		a.developerID=res.getInt("developer_id");

		a.lastUpdated=DatabaseUtils.getInstant(res, "last_updated");

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
		if(logo==null)
			return null;
		if(logo instanceof LocalImage li){
			return li;
		}
		return null;
	}

	public URI getActivityPubID(){
		return Config.localURI("/apps/"+id);
	}
}
