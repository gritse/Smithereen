package smithereen.model.comments;

import smithereen.model.ObfuscatedObjectIDType;
import smithereen.util.XTEA;

public record CommentParentObjectID(CommentableObjectType type, long id){
	public String getHtmlElementID(){
		return switch(type){
			case PHOTO -> "photo_"+XTEA.encodeObjectID(id, ObfuscatedObjectIDType.PHOTO);
			case BOARD_TOPIC -> "topic_"+XTEA.encodeObjectID(id, ObfuscatedObjectIDType.BOARD_TOPIC);
		};
	}

	public String getApiType(){
		return switch(type){
			case PHOTO -> "photo";
			case BOARD_TOPIC -> "topic";
		};
	}

	public String getIdString(){
		return switch(type){
			case PHOTO -> XTEA.encodeObjectID(id, ObfuscatedObjectIDType.PHOTO);
			case BOARD_TOPIC -> XTEA.encodeObjectID(id, ObfuscatedObjectIDType.BOARD_TOPIC);
		};
	}
}
