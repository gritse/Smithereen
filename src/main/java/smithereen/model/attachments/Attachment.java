package smithereen.model.attachments;

import org.jetbrains.annotations.Nullable;

import smithereen.util.TranslatableEnum;

public abstract sealed class Attachment permits PhotoAttachment{

	@Nullable
	public String mediaType;

	@Nullable
	public String description;

	public abstract Type getType();

	public enum Type implements TranslatableEnum<Type>{
		PHOTO;

		@Override
		public String getLangKey(){
			return switch(this){
				case PHOTO -> "content_type_photo";
			};
		}
	}
}
