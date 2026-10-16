package smithereen.routes;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import smithereen.ApplicationContext;
import smithereen.BuildInfo;
import smithereen.Config;
import smithereen.LruCache;
import smithereen.Utils;
import smithereen.model.objects.StoredObject;
import smithereen.model.objects.Actor;
import smithereen.media.ProcessingOptions;
import smithereen.model.media.Audio;
import smithereen.model.media.MediaFileRef;
import smithereen.templates.AudioRenderer;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.templates.VideoRenderer;
import smithereen.storage.MediaFileResolver;
import smithereen.model.media.Video;
import smithereen.model.media.MediaFileStatus;
import smithereen.api.methods.ApiUtils;
import smithereen.controllers.ObjectLinkResolver;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.exceptions.UserErrorException;
import smithereen.lang.Lang;
import smithereen.model.Account;
import smithereen.model.AttachmentHostContentObject;
import smithereen.model.BlurHashable;
import smithereen.model.CaptchaInfo;
import smithereen.model.Group;
import smithereen.model.MailMessage;
import smithereen.model.ObfuscatedObjectIDType;
import smithereen.model.OwnedContentObject;
import smithereen.model.Poll;
import smithereen.model.Post;
import smithereen.model.ServerRule;
import smithereen.model.SessionInfo;
import smithereen.model.SizedImage;
import smithereen.model.User;
import smithereen.model.UserInteractions;
import smithereen.model.WebDeltaResponse;
import smithereen.model.admin.ViolationReport;
import smithereen.model.apps.ClientApp;
import smithereen.model.attachments.GraffitiAttachment;
import smithereen.model.board.BoardTopic;
import smithereen.model.comments.Comment;
import smithereen.model.groups.GroupLink;
import smithereen.model.media.MediaFileUploadPurpose;
import smithereen.model.media.PhotoViewerInlineData;
import smithereen.model.photos.Photo;
import smithereen.model.photos.PhotoAlbum;
import smithereen.model.reports.ReportableContentObject;
import smithereen.model.util.QuickSearchResults;
import smithereen.model.viewmodel.PostViewModel;
import smithereen.storage.CommentStorage;
import smithereen.storage.DatabaseUtils;
import smithereen.storage.GroupStorage;
import smithereen.storage.MailStorage;
import smithereen.storage.MediaStorageUtils;
import smithereen.storage.PhotoStorage;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.templates.RenderedTemplateResponse;
import smithereen.text.TextProcessor;
import smithereen.util.CaptchaGenerator;
import smithereen.util.CharacterRange;
import smithereen.util.CryptoUtils;
import smithereen.util.JsonArrayBuilder;
import smithereen.util.JsonObjectBuilder;
import smithereen.util.NamedMutexCollection;
import smithereen.util.UriBuilder;
import smithereen.util.XTEA;
import spark.QueryParamsMap;
import spark.Request;
import spark.Response;
import spark.utils.StringUtils;

import static smithereen.Utils.*;

public class SystemRoutes{
	private static final Logger LOG=LoggerFactory.getLogger(SystemRoutes.class);
	private static final String privacyPolicyHTML;

	static{
		try(InputStreamReader reader=new InputStreamReader(SystemRoutes.class.getResourceAsStream("/privacy-policy.md"))){
			Parser mdParser=new Parser.Builder().build();
			HtmlRenderer mdRenderer=new HtmlRenderer.Builder().build();
			String html=mdRenderer.render(mdParser.parseReader(reader));
			PebbleTemplate template=new PebbleEngine.Builder()
					.defaultEscapingStrategy("html")
					.build()
					.getLiteralTemplate(html);
			StringWriter writer=new StringWriter();
			template.evaluate(writer, Map.of("domain", Config.domain));
			org.jsoup.nodes.Document doc=org.jsoup.parser.Parser.parseBodyFragment(writer.toString(), "");
			doc.select("ul").forEach(el->el.addClass("actualList"));
			doc.select("li").forEach(el->{
				Element span=doc.createElement("span");
				el.childNodes().forEach(span::appendChild);
				el.appendChild(span);
			});
			doc.select("h1").forEach(el->el.tagName("h3"));
			privacyPolicyHTML=doc.body().html();
		}catch(IOException x){
			throw new RuntimeException(x);
		}
	}

	public static Object uploadPostPhoto(Request req, Response resp, Account self, ApplicationContext ctx){
		boolean isGraffiti=req.queryParams("graffiti")!=null;
		return uploadPhotoAttachment(req, resp, self, isGraffiti);
	}

	public static Object uploadMessagePhoto(Request req, Response resp, Account self, ApplicationContext ctx){
		return uploadPhotoAttachment(req, resp, self, false);
	}

	private static Object uploadPhotoAttachment(Request req, Response resp, Account self, boolean isGraffiti){
		MediaFileRef uploaded=MediaStorageUtils.saveUploadedMedia(req, resp, self, "file", MediaFileUploadPurpose.ATTACHMENT, isGraffiti ? ProcessingOptions.GRAFFITI : ProcessingOptions.DEFAULT);
		if(isAjax(req)){
			resp.type("application/json");
			String localID=uploaded.getLocalID(MediaFileUploadPurpose.ATTACHMENT, self.user.id);
			SizedImage preview=uploaded.previewImage();
			JsonObjectBuilder result=new JsonObjectBuilder()
					.add("id", localID)
					.add("kind", uploaded.kind().toString().toLowerCase())
					.add("width", uploaded.getWidth())
					.add("height", uploaded.getHeight())
					.add("title", uploaded.getDisplayTitle());
			if(preview!=null){
				result.add("thumbs", new JsonObjectBuilder()
						.add("jpeg", preview.getUriForSizeAndFormat(SizedImage.Type.PHOTO_THUMB_SMALL, SizedImage.Format.JPEG).toString())
						.add("webp", preview.getUriForSizeAndFormat(SizedImage.Type.PHOTO_THUMB_SMALL, SizedImage.Format.WEBP).toString())
				);
			}
			SizedImage photo=uploaded.image();
			if(photo!=null){
				PhotoViewerInlineData pvData=new PhotoViewerInlineData(0, "rawFile/"+localID, photo.getURLsForPhotoViewer());
				result.add("pv", gson.toJsonTree(pvData));
			}
			return result.build();
		}
		resp.redirect(back(req));
		return "";
	}

	/**
	 * Tells a page whether the video attachments it shows as "processing" are done, with their new markup.
	 * The fallback for the websocket message, for pages that were loaded before the websocket connected
	 * or by viewers other than the uploader.
	 */
	public static Object mediaStatus(Request req, Response resp){
		String ids=req.queryParams("ids");
		if(StringUtils.isEmpty(ids))
			throw new BadRequestException();
		Lang l=lang(req);
		JsonObjectBuilder result=new JsonObjectBuilder();
		int count=0;
		for(String encodedID:ids.split(",")){
			if(++count>20)
				break;
			long fileID;
			try{
				fileID=XTEA.decodeObjectID(encodedID, ObfuscatedObjectIDType.MEDIA_FILE);
			}catch(Exception x){
				continue;
			}
			MediaFileRef ref=new MediaFileRef(fileID);
			try{
				MediaFileResolver.resolve(ref);
			}catch(SQLException x){
				throw new InternalServerErrorException(x);
			}
			if(ref.fileRecord==null || ref.fileRecord.status()==MediaFileStatus.PROCESSING)
				continue;
			switch(ref.media()){
				case Video video -> result.add(encodedID, VideoRenderer.render(video, null, l));
				case Audio audio -> result.add(encodedID, AudioRenderer.render(audio, null, l));
				default -> {}
			}
		}
		resp.type("application/json");
		return result.build();
	}

	public static Object aboutServer(Request req, Response resp){
		ApplicationContext ctx=context(req);
		RenderedTemplateResponse model=new RenderedTemplateResponse("about_server", req);
		model.with("title", lang(req).get("about_server"));
		model.with("serverPolicy", Config.serverPolicy)
				.with("serverAdmins", ctx.getModerationController().getPublicServerAdmins())
				.with("serverAdminEmail", Config.serverAdminEmail)
				.with("totalUsers", ctx.getUsersController().getLocalUserCount())
				.with("totalPosts", ctx.getWallController().getLocalPostCount(false))
				.with("totalGroups", ctx.getGroupsController().getLocalGroupCount())
				.with("serverVersion", BuildInfo.VERSION)
				.with("serverRules", ctx.getModerationController().getServerRules());

		return model;
	}

	public static Object quickSearch(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "q");
		String query=req.queryParams("q").trim();
		QuickSearchResults res=ctx.getSearchController().quickSearch(query, self.user);
		return new RenderedTemplateResponse("quick_search_results", req).with("users", res.users()).with("groups", res.groups()).with("avaSize", req.attribute("mobile")!=null ? 48 : 30);
	}

	public static Object votePoll(Request req, Response resp, Account self, ApplicationContext ctx){
		int id=parseIntOrDefault(req.queryParams("id"), 0);
		if(id==0)
			throw new ObjectNotFoundException();
		Poll poll=ctx.getWallController().getPollByID(id);


		String[] _options=req.queryMap("option").values();
		if(_options.length<1)
			throw new BadRequestException("options param is empty");
		if(_options.length!=1 && !poll.multipleChoice)
			throw new BadRequestException("invalid option count");
		if(_options.length>poll.options.size())
			throw new BadRequestException("invalid option count");
		int[] optionIDs=new int[_options.length];

		for(int i=0;i<_options.length;i++){
			int optID=parseIntOrDefault(_options[i], 0);
			if(optID<=0)
				throw new BadRequestException("invalid option id '"+_options[i]+"'");
			optionIDs[i]=optID;
		}

		ctx.getWallController().voteInPoll(self.user, poll, optionIDs);

		if(isAjax(req)){
			UserInteractions interactions=new UserInteractions();
			interactions.pollChoices=Arrays.stream(optionIDs).boxed().collect(Collectors.toList());
			RenderedTemplateResponse model=new RenderedTemplateResponse("poll", req).with("poll", poll).with("interactions", interactions);
			model.with("post", new PostViewModel(ctx.getWallController().getPostOrThrow(ctx.getWallController().getPostIDForPoll(poll))));
			return new WebDeltaResponse(resp).setContent("poll"+poll.id, model.renderBlock("inner"));
		}

		resp.redirect(back(req));
		return "";
	}

	public static Object reportForm(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "type", "id");
		RenderedTemplateResponse model=new RenderedTemplateResponse("report_form", req);
		String rawID=req.queryParams("id");
		Actor actorForAvatar;
		String title, subtitle, boxTitle, titleText;
		Lang l=lang(req);
		String type=req.queryParams("type");
		switch(type){
			case "post" -> {
				int id=safeParseInt(rawID);
				Post post=ctx.getWallController().getPostOrThrow(id);
				User postAuthor=null;
				try{
					postAuthor=ctx.getUsersController().getUserOrThrow(post.authorID);
				}catch(ObjectNotFoundException ignore){}
				actorForAvatar=postAuthor;
				title=postAuthor==null ? "DELETED" : postAuthor.getCompleteName();
				subtitle=TextProcessor.truncateOnWordBoundary(post.text, 200);
				boxTitle=l.get(post.getReplyLevel()>0 ? "report_title_comment" : "report_title_post");
				titleText=l.get(post.getReplyLevel()>0 ? "report_text_comment" : "report_text_post");
			}
			case "user" -> {
				int id=safeParseInt(rawID);
				User user=ctx.getUsersController().getUserOrThrow(id);
				actorForAvatar=user;
				title=user.getCompleteName();
				subtitle="";
				boxTitle=l.get("report_title_user");
				titleText=l.get("report_text_user");
			}
			case "group" -> {
				int id=safeParseInt(rawID);
				Group group=ctx.getGroupsController().getGroupOrThrow(id);
				actorForAvatar=group;
				title=group.name;
				subtitle="";
				boxTitle=l.get(group.isEvent() ? "report_title_event" : "report_title_group");
				titleText=l.get(group.isEvent() ? "report_text_event" : "report_text_group");
			}
			case "message" -> {
				long id=XTEA.decodeObjectID(rawID, ObfuscatedObjectIDType.MAIL_MESSAGE);
				MailMessage msg=ctx.getMailController().getMessage(self.user, id, false);
				User user=ctx.getUsersController().getUserOrThrow(msg.senderID);
				actorForAvatar=user;
				title=user.getCompleteName();
				subtitle=TextProcessor.truncateOnWordBoundary(msg.text, 200);
				boxTitle=l.get("report_title_message");
				titleText=l.get("report_text_message");
			}
			case "photo" -> {
				long id=XTEA.decodeObjectID(rawID, ObfuscatedObjectIDType.PHOTO);
				Photo photo=ctx.getPhotosController().getPhotoIgnoringPrivacy(id);
				ctx.getPrivacyController().enforceObjectPrivacy(self.user, photo);
				User user=null;
				try{
					user=ctx.getUsersController().getUserOrThrow(photo.authorID);
				}catch(ObjectNotFoundException ignore){}
				actorForAvatar=user;
				title=user==null ? "DELETED" : user.getCompleteName();
				subtitle=photo.description;
				boxTitle=l.get("report_title_photo");
				titleText=l.get("report_text_photo");
			}
			case "comment" -> {
				long id=XTEA.decodeObjectID(rawID, ObfuscatedObjectIDType.COMMENT);
				Comment comment=ctx.getCommentsController().getCommentIgnoringPrivacy(id);
				ctx.getPrivacyController().enforceObjectPrivacy(self.user, comment);
				User user=null;
				try{
					user=ctx.getUsersController().getUserOrThrow(comment.authorID);
				}catch(ObjectNotFoundException ignore){}
				actorForAvatar=user;
				title=user==null ? "DELETED" : user.getCompleteName();
				subtitle=TextProcessor.truncateOnWordBoundary(comment.text, 200);
				boxTitle=l.get("report_title_comment");
				titleText=l.get("report_text_comment");
			}
			default -> throw new BadRequestException();
		}
		model.with("actorForAvatar", actorForAvatar)
				.with("reportTitle", title)
				.with("reportSubtitle", subtitle)
				.with("reportTitleText", titleText)
				.with("serverRules", ctx.getModerationController().getServerRules());
		return wrapForm(req, resp, "report_form", "/system/submitReport?type="+type+"&id="+rawID, boxTitle, "send", model);
	}

	public static Object submitReport(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "type", "id", "reason");
		String rawID=req.queryParams("id");
		String type=req.queryParams("type");
		String comment=req.queryParamOrDefault("reportText", "");
		ViolationReport.Reason reason=enumValue(req.queryParams("reason"), ViolationReport.Reason.class);
		Set<Integer> rules;
		if(reason==ViolationReport.Reason.SERVER_RULES){
			QueryParamsMap rulesMap=req.queryMap("rules");
			if(rulesMap==null)
				throw new BadRequestException();
			Set<Integer> validRuleIDs=ctx.getModerationController()
					.getServerRules()
					.stream()
					.map(ServerRule::id)
					.collect(Collectors.toSet());
			rules=rulesMap.toMap()
					.keySet()
					.stream()
					.map(Utils::safeParseInt)
					.filter(validRuleIDs::contains)
					.collect(Collectors.toSet());
			if(rules.isEmpty())
				throw new BadRequestException();
		}else{
			rules=Set.of();
		}

		Actor target;
		List<ReportableContentObject> content;

		switch(type){
			case "post" -> {
				int id=safeParseInt(rawID);
				Post post=ctx.getWallController().getPostOrThrow(id);
				content=List.of(post);
				try{
					target=ctx.getUsersController().getUserOrThrow(post.authorID);
				}catch(ObjectNotFoundException x){
					target=null;
				}
			}
			case "user" -> {
				int id=safeParseInt(rawID);
				target=ctx.getUsersController().getUserOrThrow(id);
				content=null;
			}
			case "group" -> {
				int id=safeParseInt(rawID);
				target=ctx.getGroupsController().getGroupOrThrow(id);
				content=null;
			}
			case "message" -> {
				long id=XTEA.decodeObjectID(rawID, ObfuscatedObjectIDType.MAIL_MESSAGE);
				MailMessage msg=ctx.getMailController().getMessage(self.user, id, false);
				target=ctx.getUsersController().getUserOrThrow(msg.senderID);
				content=List.of(msg);
			}
			case "photo" -> {
				long id=XTEA.decodeObjectID(rawID, ObfuscatedObjectIDType.PHOTO);
				Photo photo=ctx.getPhotosController().getPhotoIgnoringPrivacy(id);
				ctx.getPrivacyController().enforceObjectPrivacy(self.user, photo);
				try{
					target=ctx.getUsersController().getUserOrThrow(photo.authorID);
				}catch(ObjectNotFoundException x){
					target=null;
				}
				content=List.of(photo);
			}
			case "comment" -> {
				long id=XTEA.decodeObjectID(rawID, ObfuscatedObjectIDType.COMMENT);
				Comment commentObj=ctx.getCommentsController().getCommentIgnoringPrivacy(id);
				ctx.getPrivacyController().enforceObjectPrivacy(self.user, commentObj);
				content=List.of(commentObj);
				try{
					target=ctx.getUsersController().getUserOrThrow(commentObj.authorID);
				}catch(ObjectNotFoundException x){
					target=null;
				}
			}
			default -> throw new BadRequestException("invalid type");
		}

		ctx.getModerationController().createViolationReport(self.user, target, content, reason, rules, comment);
		if(isAjax(req)){
			return new WebDeltaResponse(resp).showSnackbar(lang(req).get("report_submitted"));
		}
		return "";
	}

	public static Object captcha(Request req, Response resp) throws IOException{
		requireQueryParams(req, "sid");
		String sid=req.queryParams("sid");
		if(sid.length()>16)
			sid=sid.substring(0, 16);

		CaptchaGenerator.Captcha c=CaptchaGenerator.generate();
		LruCache<String, CaptchaInfo> captchas=req.session().attribute("captchas");
		if(captchas==null){
			captchas=new LruCache<>(10);
			req.session().attribute("captchas", captchas);
		}
		captchas.put(sid, new CaptchaInfo(c.answer(), Instant.now()));

		resp.type("image/png");
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		ImageIO.write(c.image(), "png", out);
		return out.toByteArray();
	}

	public static Object oEmbed(Request req, Response resp){
		if(!"json".equals(req.queryParamOrDefault("format", "json"))){
			resp.status(501);
			return "Unsupported format";
		}
		requireQueryParams(req, "url");
		URI url;
		try{
			url=new URI(req.queryParams("url"));
		}catch(URISyntaxException x){
			throw new BadRequestException(x);
		}
		ApplicationContext ctx=context(req);
		Post post=ctx.getObjectLinkResolver().resolveLocally(url, Post.class);
		if(post.privacy!=Post.Privacy.PUBLIC || (post.getReplyLevel()==0 && post.ownerID!=post.authorID)){
			resp.status(401);
			return "";
		}
		if(post.getReplyLevel()>0){
			Post topLevel=ctx.getWallController().getPostOrThrow(post.replyKey.getFirst());
			if(topLevel.privacy!=Post.Privacy.PUBLIC || topLevel.ownerID!=post.authorID){
				resp.status(401);
				return "";
			}
		}
		int maxWidth=Math.max(200, safeParseInt(req.queryParamOrDefault("maxwidth", "500")));
		Map<Integer, User> users=ctx.getUsersController().getUsers(Set.of(post.authorID));
		User author=users.get(post.authorID);
		if(author==null)
			throw new ObjectNotFoundException();
		resp.type("application/json");
		RenderedTemplateResponse model=new RenderedTemplateResponse("post_embed_code", req)
				.with("post", post)
				.with("users", users);
		return new JsonObjectBuilder()
				.add("version", "1.0")
				.add("type", "rich")
				.add("width", Math.min(500, maxWidth))
				.add("height", (String)null)
				.add("author_name", author.getFullName())
				.add("author_url", UriBuilder.local().rawPath(author.getFullUsername()).build().toString())
				.add("provider_name", Config.serverDisplayName)
				.add("provider_url", "https://"+Config.domain+"/")
				.add("html", model.renderToString())
				.build();
	}

	public static Object mentionCompletions(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "q");
		String query=req.queryParams("q");
		List<Pattern> normalizedQueryParts=Arrays.stream(query.toLowerCase(Locale.US).replaceAll("[()\\[\\]*+~<>\\\"@-]", " ").split("\\s+"))
				.filter(Predicate.not(String::isBlank))
				.map(s->Pattern.compile("\\b"+Pattern.quote(s), Pattern.CASE_INSENSITIVE))
				.toList();
		List<User> results=ctx.getSearchController().searchUsers(query, self.user, 10).list;
		HashMap<Integer, String> highlightedNames=new HashMap<>(), highlightedUsernames=new HashMap<>();
		List<CharacterRange> nameRanges=new ArrayList<>(), usernameRanges=new ArrayList<>();
		for(User u:results){
			nameRanges.clear();
			usernameRanges.clear();
			String name=TextProcessor.escapeHTML(u.getFullName());
			String username=TextProcessor.escapeHTML(u.getFullUsername());
			for(Pattern ptn:normalizedQueryParts){
				Matcher m=ptn.matcher(name);
				matcherLoop:
				while(m.find()){
					CharacterRange range=new CharacterRange(m.start(), m.end());
					for(CharacterRange existing:nameRanges){
						if(existing.intersects(range))
							continue matcherLoop;
					}
					nameRanges.add(range);
				}
				m=ptn.matcher(username);
				matcherLoop:
				while(m.find()){
					CharacterRange range=new CharacterRange(m.start(), m.end());
					for(CharacterRange existing:usernameRanges){
						if(existing.intersects(range))
							continue matcherLoop;
					}
					usernameRanges.add(range);
				}
			}
			nameRanges.sort(null);
			Collections.reverse(nameRanges);
			for(CharacterRange r:nameRanges){
				name=name.substring(0, r.start())+"<b>"+name.substring(r.start(), r.end())+"</b>"+name.substring(r.end());
			}
			usernameRanges.sort(null);
			Collections.reverse(usernameRanges);
			for(CharacterRange r:usernameRanges){
				username=username.substring(0, r.start())+"<b>"+username.substring(r.start(), r.end())+"</b>"+username.substring(r.end());
			}
			highlightedNames.put(u.id, name);
			highlightedUsernames.put(u.id, username);
		}
		return new RenderedTemplateResponse("mention_completions", req)
				.with("users", results)
				.with("highlightedNames", highlightedNames)
				.with("highlightedUsernames", highlightedUsernames);
	}

	public static Object simpleUserCompletions(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "q");
		String query=req.queryParams("q");
		List<Pattern> normalizedQueryParts=Arrays.stream(query.toLowerCase(Locale.US).replaceAll("[()\\[\\]*+~<>\\\"@-]", " ").split("\\s+"))
				.filter(Predicate.not(String::isBlank))
				.map(s->Pattern.compile("\\b"+Pattern.quote(s), Pattern.CASE_INSENSITIVE))
				.toList();

		List<User> results=ctx.getSearchController().searchUsers(query, self.user, 10).list;
		List<CharacterRange> nameRanges=new ArrayList<>();
		JsonArrayBuilder arr=new JsonArrayBuilder();
		for(User u:results){
			nameRanges.clear();
			String name=TextProcessor.escapeHTML(u.getFullName());
			for(Pattern ptn:normalizedQueryParts){
				Matcher m=ptn.matcher(name);
				matcherLoop:
				while(m.find()){
					CharacterRange range=new CharacterRange(m.start(), m.end());
					for(CharacterRange existing:nameRanges){
						if(existing.intersects(range))
							continue matcherLoop;
					}
					nameRanges.add(range);
				}
			}
			nameRanges.sort(null);
			Collections.reverse(nameRanges);
			for(CharacterRange r:nameRanges){
				name=name.substring(0, r.start())+"<b>"+name.substring(r.start(), r.end())+"</b>"+name.substring(r.end());
			}
			arr.add(new JsonObjectBuilder().add("id", u.id).add("title", name));
		}
		return arr.build();
	}

	public static Object privacyPolicy(Request req, Response resp){
		Lang l=lang(req);
		return new RenderedTemplateResponse("privacy_policy", req)
				.with("privacyPolicy", privacyPolicyHTML)
				.pageTitle(l.get("privacy_policy"))
				.addNavBarItem(l.get("footer_about_server"), "/system/about")
				.addNavBarItem(l.get("privacy_policy"));
	}

	public static Object languageChooser(Request req, Response resp){
		if(!isAjax(req))
			throw new BadRequestException();
		RenderedTemplateResponse model=new RenderedTemplateResponse("language_chooser_box", req)
				.with("currentLang", lang(req))
				.with("languages", Lang.list);
		return new WebDeltaResponse(resp).box(lang(req).get("choose_language_title"), model.renderToString(), null, true, "<span class=\"inlineLoader\" style=\"display: none\" id=\"langChooserLoader\"></span>");
	}
}
