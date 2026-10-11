package smithereen.controllers;

import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.activitypub.objects.Actor;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.model.Group;
import smithereen.model.PaginatedList;
import smithereen.model.Post;
import smithereen.model.SearchResult;
import smithereen.model.User;
import smithereen.model.board.BoardTopic;
import smithereen.model.comments.Comment;
import smithereen.model.comments.CommentReplyParent;
import smithereen.model.photos.Photo;
import smithereen.model.photos.PhotoAlbum;
import smithereen.model.util.QuickSearchResults;
import smithereen.storage.GroupStorage;
import smithereen.storage.SearchStorage;
import smithereen.storage.UserStorage;
import smithereen.text.TextProcessor;
import spark.utils.StringUtils;

import static smithereen.Utils.*;

public class SearchController{
	private static final Logger LOG=LoggerFactory.getLogger(SearchController.class);

	private final ApplicationContext context;

	public SearchController(ApplicationContext context){
		this.context=context;
	}

	public QuickSearchResults quickSearch(String query, User self){
		try{
			if(StringUtils.isEmpty(query) || query.length()<2)
				return new QuickSearchResults(List.of(), List.of());

			List<User> users=List.of();
			List<Group> groups=List.of();
			if(isURL(query)){
				if(!query.startsWith("http:") && !query.startsWith("https:"))
					query="https://"+query;
				query=normalizeURLDomain(query);
				URI uri=URI.create(query);
				if(Config.isLocal(uri)){
					try{
						Object obj=context.getObjectLinkResolver().resolveLocally(uri, Object.class);
						if(obj instanceof User u){
							users=List.of(u);
						}else if(obj instanceof Group g){
							groups=List.of(g);
						}
					}catch(ObjectNotFoundException|IllegalStateException ignore){}
				}
			}else if(isUsernameAndDomain(query)){
				Matcher matcher=TextProcessor.USERNAME_DOMAIN_PATTERN.matcher(query);
				matcher.find();
				String username=matcher.group(1);
				String domain=matcher.group(2);
				if(domain==null || domain.equalsIgnoreCase(Config.domain)){
					User user=UserStorage.getByUsername(username);
					if(user!=null){
						users=List.of(user);
					}else{
						Group group=GroupStorage.getByUsername(username);
						if(group!=null)
							groups=List.of(group);
					}
				}
			}else{
				List<SearchResult> results=SearchStorage.search(query, self.id, 10);
				users=new ArrayList<>();
				groups=new ArrayList<>();
				for(SearchResult result:results){
					switch(result.type){
						case USER -> users.add(result.user);
						case GROUP -> groups.add(result.group);
					}
				}
			}
			return new QuickSearchResults(users, groups);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<Integer> searchUserIDs(String query, User self, int count){
		try{
			return SearchStorage.searchUsers(query, self.id, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<User> searchUsers(String query, User self, int count){
		try{
			PaginatedList<Integer> ids=SearchStorage.searchUsers(query, self.id, count);
			return new PaginatedList<>(ids, UserStorage.getByIdAsList(ids.list));
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<User> searchFriends(String query, User user, int offset, int count, FriendsController.SortOrder order){
		try{
			PaginatedList<Integer> ids=SearchStorage.searchFriends(query, user.id, offset, count, order==FriendsController.SortOrder.HINTS);
			return new PaginatedList<>(ids, UserStorage.getByIdAsList(ids.list));
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<Integer> searchAllGroupIDs(String query, boolean events, int offset, int count){
		try{
			return SearchStorage.searchAllGroups(query, events, offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<Group> searchGroups(User self, String query, boolean events, User target, int offset, int count){
		try{
			PaginatedList<Integer> ids=SearchStorage.searchGroups(query, events, target.id, offset, count, self!=null && self.id==target.id);
			return new PaginatedList<>(ids, GroupStorage.getByIdAsList(ids.list));
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

}
