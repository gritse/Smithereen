package smithereen.model.groups;


import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;

import smithereen.activitypub.objects.LocalImage;
import smithereen.controllers.ObjectLinkResolver;
import smithereen.model.SizedImage;

public class GroupLink{
	public long id;
	public int groupID;
	public URI url;
	public String title;
	public ObjectLinkResolver.ObjectTypeAndID object;
	public SizedImage image;
	public int displayOrder;
	public URI localUrl;

	public static GroupLink fromResultSet(ResultSet res) throws SQLException{
		GroupLink l=new GroupLink();
		l.id=res.getLong("id");
		l.groupID=res.getInt("group_id");
		l.url=URI.create(res.getString("url"));
		l.title=res.getString("title");
		l.displayOrder=res.getInt("display_order");

		int objType=res.getInt("object_type");
		if(!res.wasNull()){
			l.object=new ObjectLinkResolver.ObjectTypeAndID(ObjectLinkResolver.ObjectType.fromID(objType), res.getLong("object_id"));
		}
		long imageID=res.getLong("image_id");
		if(imageID!=0){
			l.image=new LocalImage(imageID);
		}

		return l;
	}

	public SizedImage getImage(){
		return image;
	}

	public String getTitleLangKey(){
		if(object==null)
			return null;
		return switch(object.type()){
			case POST -> "content_type_post";
			case PHOTO_ALBUM -> "photo_album";
			case PHOTO -> "content_type_photo";
			case COMMENT -> "content_type_comment";
			case BOARD_TOPIC -> "board_topic";
			default -> null;
		};
	}

	public String getDescription(){
		return object!=null ? title : url.getHost();
	}

	public URI getUrl(){
		return localUrl==null ? url : localUrl;
	}
}
