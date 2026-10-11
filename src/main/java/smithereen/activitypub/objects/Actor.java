package smithereen.activitypub.objects;

import com.google.gson.JsonParser;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;

import smithereen.Config;
import smithereen.model.ActorStatus;
import smithereen.model.SizedImage;
import spark.utils.StringUtils;

public abstract class Actor extends ActivityPubObject{
	public static final int USERNAME_MAX_LENGTH=64;

	public String username;
	public ActorStatus status;

	public String aboutSource;

	public String getProfileURL(String action){
		return "/"+username+"/"+action;
	}

	public String getProfileURL(){
		return "/"+username;
	}

	public String getAbsoluteProfileURL(){
		return Config.localURI(getProfileURL()).toString();
	}

	public boolean hasAvatar(){
		Image img=getBestAvatarImage();
		return img!=null && (img instanceof LocalImage || img.url!=null);
	}

	public Image getAvatarImage(){
		if(icon!=null && !icon.isEmpty())
			return icon.getFirst();
		return null;
	}

	public Image getBestAvatarImage(){
		Image icon=this.icon!=null ? this.icon.getFirst() : null;
		if(icon==null)
			return null;
		if(icon instanceof LocalImage)
			return icon;
		if(icon.image!=null && !icon.image.isEmpty() && icon.image.getFirst().width>0 && icon.image.getFirst().height>0)
			return icon.image.getFirst();

		if(this.icon.size()==1)
			return icon;
		Image largest=null;
		int largestArea=0;
		for(Image img:this.icon){
			int area=img.width*img.height;
			if(area>largestArea){
				largestArea=area;
				largest=img;
			}
		}
		return largest;
	}

	public float[] getAvatarCropRegion(){
		Image icon=this.icon!=null ? this.icon.getFirst() : null;
		if(icon==null)
			return null;
		return icon.cropRegion;
	}

	public String getFullUsername(){
		return username;
	}

	public SizedImage getAvatar(){
		Image icon=getBestAvatarImage();
		if(icon instanceof LocalImage li)
			return li;
		return null;
	}

	public abstract int getLocalID();
	public abstract String getTypeAndIdForURL();
	public abstract String getName();
	public abstract String serializeProfileFields();

	protected void fillFromResultSet(ResultSet res) throws SQLException{
		String _ava=res.getString("avatar");
		if(_ava!=null){
			if(_ava.startsWith("{")){
				try{
					icon=Collections.singletonList((Image)ActivityPubObject.parse(JsonParser.parseString(_ava).getAsJsonObject()));
				}catch(Exception ignore){}
			}
		}

		username=res.getString("username");
		aboutSource=res.getString("about_source");
	}

	public String getAboutSource(){
		return StringUtils.isNotEmpty(aboutSource) ? aboutSource : summary;
	}

	public int getOwnerID(){
		throw new UnsupportedOperationException();
	}

	public String getStatusText(){
		return status!=null && !status.isExpired() ? status.text() : null;
	}
}
