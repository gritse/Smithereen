package smithereen.api.methods;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.api.ApiCallContext;
import smithereen.api.ApiErrorException;
import smithereen.api.model.ApiErrorType;
import smithereen.api.model.ApiValidationError;
import smithereen.controllers.ObjectLinkResolver;
import smithereen.model.Group;
import smithereen.model.Post;
import smithereen.model.User;
import smithereen.model.board.BoardTopic;
import smithereen.model.comments.Comment;
import smithereen.model.photos.Photo;
import smithereen.model.photos.PhotoAlbum;
import smithereen.util.CryptoUtils;

public class UtilsMethods{
	public static Object getServerTime(ApplicationContext ctx, ApiCallContext actx){
		return System.currentTimeMillis()/1000L;
	}

	public static Object resolveScreenName(ApplicationContext ctx, ApiCallContext actx){
		String screenName=actx.requireParamString("screen_name");
		ObjectLinkResolver.UsernameResolutionResult res=ctx.getObjectLinkResolver().resolveUsernameLocally(screenName);
		record ScreenNameResult(String type, long id){}
		return new ScreenNameResult(switch(res.type()){
			case USER -> "user";
			case GROUP -> "group";
			case APPLICATION -> "application";
		}, res.localID());
	}

	public static Object testCaptcha(ApplicationContext ctx, ApiCallContext actx){
		ApiUtils.enforceCaptcha(ctx, actx);
		return true;
	}

	public static Object testValidation(ApplicationContext ctx, ApiCallContext actx){
		String successStr=Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtils.sha256("Success!".getBytes(StandardCharsets.UTF_8)));
		if(!successStr.equals(actx.optParamString("validation_key")))
			throw new ApiErrorException(new ApiValidationError(ApiErrorType.VALIDATION_NEEDED, null, actx.params, Config.localURI("/api/testValidation?this_parameter=should%20be%20kept%20intact&this_one=as%20well").toString()));
		return true;
	}
}
