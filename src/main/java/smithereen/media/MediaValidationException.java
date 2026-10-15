package smithereen.media;

import java.util.Map;

/**
 * An uploaded file was rejected: wrong kind for the purpose, unreadable, too large, wrong dimensions.
 * Carries the HTTP status and the translated message key so that routes can report it without knowing why.
 */
public class MediaValidationException extends Exception{
	public final int httpStatus;
	public final String langKey;
	public final Map<String, Object> langArgs;

	public MediaValidationException(int httpStatus, String langKey){
		this(httpStatus, langKey, Map.of());
	}

	public MediaValidationException(int httpStatus, String langKey, Map<String, Object> langArgs){
		super(langKey);
		this.httpStatus=httpStatus;
		this.langKey=langKey;
		this.langArgs=langArgs;
	}

	/** 415 Unsupported Media Type */
	public static MediaValidationException unsupportedFormat(){
		return new MediaValidationException(415, "err_file_upload_image_format");
	}

	/** 413 Payload Too Large; the route formats the size for the message */
	public static MediaValidationException tooLarge(long maxSizeBytes){
		return new MediaValidationException(413, "err_file_upload_too_large", Map.of("maxSizeBytes", maxSizeBytes));
	}

	/** 400 Bad Request: the file claims to be an image but can't be decoded */
	public static MediaValidationException unreadable(){
		return new MediaValidationException(400, "err_file_upload_image_format");
	}
}
