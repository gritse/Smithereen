package smithereen.storage;

import com.google.gson.JsonObject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import smithereen.Config;
import smithereen.LruCache;
import smithereen.Utils;
import smithereen.activitypub.objects.Actor;
import smithereen.activitypub.objects.LocalImage;
import smithereen.controllers.GroupsController;
import smithereen.controllers.ObjectLinkResolver;
import smithereen.model.Group;
import smithereen.model.PaginatedList;
import smithereen.model.User;
import smithereen.model.admin.GroupActionLogAction;
import smithereen.model.groups.GroupAdmin;
import smithereen.model.groups.GroupBanStatus;
import smithereen.model.groups.GroupInvitation;
import smithereen.model.groups.GroupLink;
import smithereen.model.media.MediaFileRecord;
import smithereen.model.notifications.UserNotifications;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.storage.sql.SQLQueryBuilder;
import smithereen.storage.utils.IntPair;
import smithereen.storage.utils.Pair;
import smithereen.text.TextProcessor;
import smithereen.util.NamedMutexCollection;
import spark.utils.StringUtils;

public class GroupStorage{
	private static final Logger LOG=LoggerFactory.getLogger(GroupStorage.class);

	private static final LruCache<Integer, Group> cacheByID=new LruCache<>(500);
	private static final LruCache<String, Group> cacheByUsername=new LruCache<>(500);

	private static final Object adminUpdateLock=new Object();

	public static int createGroup(String name, String description, String descriptionSrc, int userID, boolean isEvent, Instant eventStart, Instant eventEnd) throws SQLException{
		int id;
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			try{
				conn.createStatement().execute("START TRANSACTION");

				String username;
				id=new SQLQueryBuilder(conn)
						.insertInto("groups")
						.value("name", name)
						.value("username", "__tmp"+System.currentTimeMillis())
						.value("member_count", 1)
						.value("about", description)
						.value("about_source", descriptionSrc)
						.value("event_start_time", eventStart)
						.value("event_end_time", eventEnd)
						.value("type", isEvent ? Group.Type.EVENT : Group.Type.GROUP)
						.executeAndGetID();

				username=(isEvent ? "event" : "club")+id;

				new SQLQueryBuilder(conn)
						.update("groups")
						.value("username", username)
						.where("id=?", id)
						.executeNoResult();

				new SQLQueryBuilder(conn)
						.insertInto("group_memberships")
						.value("user_id", userID)
						.value("group_id", id)
						.executeNoResult();

				new SQLQueryBuilder(conn)
						.insertInto("group_admins")
						.value("user_id", userID)
						.value("group_id", id)
						.value("level", Group.AdminLevel.OWNER)
						.executeNoResult();

				new SQLQueryBuilder(conn)
						.insertInto("qsearch_index")
						.value("string", TextProcessor.transliterate(name)+" "+username)
						.value("group_id", id)
						.executeNoResult();
			}catch(Exception x){
				conn.createStatement().execute("ROLLBACK");
				throw new SQLException(x);
			}
			SessionStorage.removeFromUserPermissionsCache(userID);
			conn.createStatement().execute("COMMIT");
		}
		return id;
	}

	@Nullable
	public static Group getById(int id) throws SQLException{
		Group g=cacheByID.get(id);
		if(g!=null)
			return g;
		g=new SQLQueryBuilder()
				.selectFrom("groups")
				.allColumns()
				.where("id=?", id)
				.executeAndGetSingleObject(Group::fromResultSet);
		if(g!=null){
			if(g.icon!=null && !g.icon.isEmpty() && g.icon.getFirst() instanceof LocalImage li){
				MediaFileRecord mfr=MediaStorage.getMediaFileRecord(li.fileID);
				if(mfr!=null)
					li.fillIn(mfr);
			}
			putIntoCache(g);
		}
		return g;
	}

	public static Group getByUsername(String username) throws SQLException{
		Group g=cacheByUsername.get(username.toLowerCase());
		if(g!=null)
			return g;
		g=new SQLQueryBuilder()
				.selectFrom("groups")
				.allColumns()
				.where("username=?", username)
				.executeAndGetSingleObject(Group::fromResultSet);
		if(g!=null){
			if(g.icon!=null && !g.icon.isEmpty() && g.icon.getFirst() instanceof LocalImage li){
				MediaFileRecord mfr=MediaStorage.getMediaFileRecord(li.fileID);
				if(mfr!=null)
					li.fillIn(mfr);
			}
			putIntoCache(g);
		}
		return g;
	}

	public static int getIdByUsername(@NotNull String username) throws SQLException{
		if(username.length()>Actor.USERNAME_MAX_LENGTH)
			username=username.substring(0, Actor.USERNAME_MAX_LENGTH);
		return new SQLQueryBuilder()
				.selectFrom("groups")
				.columns("id")
				.where("username=?", username)
				.executeAndGetInt();
	}

	public static Map<String, Integer> getIdsByUsernames(Collection<String> usernames) throws SQLException{
		if(usernames.isEmpty())
			return Map.of();
		HashSet<String> remainingUsernames=new HashSet<>();
		Map<String, Integer> ids=new HashMap<>();
		for(String u:usernames){
			Group g=cacheByUsername.get(u.toLowerCase());
			if(g!=null){
				ids.put(u, g.id);
				continue;
			}
			remainingUsernames.add(u);
		}
		if(!remainingUsernames.isEmpty()){
			new SQLQueryBuilder()
					.selectFrom("groups")
					.columns("id", "username")
					.whereIn("username", remainingUsernames)
					.executeAsStream(r->new Pair<>(r.getString("username"), r.getInt("id")))
					.forEach(p->{
						// TODO improve caching
						ids.put(p.first(), p.second());
					});
		}
		return ids;
	}

	public static List<Group> getByIdAsList(Collection<Integer> ids) throws SQLException{
		if(ids.isEmpty())
			return Collections.emptyList();
		if(ids.size()==1){
			for(int id:ids)
				return Collections.singletonList(getById(id));
		}
		Map<Integer, Group> groups=getById(ids);
		return ids.stream().map(groups::get).filter(Objects::nonNull).collect(Collectors.toList());
	}

	public static Map<Integer, Group> getById(Collection<Integer> _ids) throws SQLException{
		if(_ids.isEmpty())
			return Collections.emptyMap();
		if(_ids.size()==1){
			for(int id:_ids){
				return Collections.singletonMap(id, getById(id));
			}
		}
		Set<Integer> ids=new HashSet<>(_ids);
		Map<Integer, Group> result=new HashMap<>(ids.size());
		Iterator<Integer> itr=ids.iterator();
		while(itr.hasNext()){
			Integer id=itr.next();
			Group group=cacheByID.get(id);
			if(group!=null){
				itr.remove();
				result.put(id, group);
			}
		}
		if(ids.isEmpty())
			return result;
		result.putAll(new SQLQueryBuilder()
				.selectFrom("groups")
				.allColumns()
				.whereIn("id", ids)
				.executeAsStream(Group::fromResultSet)
				.collect(Collectors.toMap(g->g.id, Function.identity())));
		Set<Long> needAvatars=result.values().stream()
				.map(g->g.icon!=null && !g.icon.isEmpty() && g.icon.getFirst() instanceof LocalImage li ? li : null)
				.filter(li->li!=null && li.fileRecord==null)
				.map(li->li.fileID)
				.collect(Collectors.toSet());
		if(!needAvatars.isEmpty()){
			Map<Long, MediaFileRecord> records=MediaStorage.getMediaFileRecords(needAvatars);
			for(Group group:result.values()){
				if(group.icon!=null && !group.icon.isEmpty() && group.icon.getFirst() instanceof LocalImage li && li.fileRecord==null){
					MediaFileRecord mfr=records.get(li.fileID);
					if(mfr!=null)
						li.fillIn(mfr);
				}
			}
		}
		for(Group g:result.values()){
			putIntoCache(g);
		}
		return result;
	}

	public static List<Integer> getRandomMembers(int groupID, Boolean tentative, int count) throws SQLException{
		return new SQLQueryBuilder()
						.selectFrom("group_memberships")
						.columns("user_id")
						.where("group_id=? AND accepted=1"+(tentative==null ? "" : " AND tentative=?"), groupID, tentative)
						.orderBy("RAND()")
						.limit(count, 0)
						.executeAndGetIntList();
	}

	public static PaginatedList<Integer> getMembers(int groupID, int offset, int count, @Nullable Boolean tentative, GroupsController.MemberSortOrder order) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			String _tentative=tentative==null ? "" : (" AND tentative="+(tentative ? '1' : '0'));
			int total=new SQLQueryBuilder(conn)
					.selectFrom("group_memberships")
					.count()
					.where("group_id=? AND accepted=1"+_tentative, groupID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			List<Integer> ids=new SQLQueryBuilder(conn)
					.selectFrom("group_memberships")
					.columns("user_id")
					.where("group_id=? AND accepted=1"+_tentative, groupID)
					.orderBy(switch(order){
						case ID_ASC -> "user_id ASC";
						case ID_DESC -> "user_id DESC";
						case TIME_ASC -> "time ASC";
						case TIME_DESC -> "time DESC";
						case RANDOM -> throw new IllegalArgumentException();
					})
					.limit(count, offset)
					.executeAndGetIntList();
			return new PaginatedList<>(ids, total, offset, count);
		}
	}

	public static List<Integer> getRandomFriendsMembers(int groupID, Boolean tentative, int count, int userID) throws SQLException{
		String _tentative=tentative==null ? "" : (" AND tentative="+(tentative ? '1' : '0'));
		return new SQLQueryBuilder()
				.selectFrom("group_memberships")
				.columns("user_id")
				.join("RIGHT JOIN followings ON user_id=followee_id")
				.where("group_id=? AND group_memberships.accepted=1 AND followings.accepted=1 AND mutual=1 AND follower_id=?"+_tentative, groupID, userID)
				.orderBy("RAND()")
				.limit(count, 0)
				.executeAndGetIntList();
	}

	public static PaginatedList<Integer> getFriendsMembers(int groupID, int offset, int count, @Nullable Boolean tentative, GroupsController.MemberSortOrder order, int userID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			String _tentative=tentative==null ? "" : (" AND tentative="+(tentative ? '1' : '0'));
			int total=new SQLQueryBuilder(conn)
					.selectFrom("group_memberships")
					.count()
					.join("RIGHT JOIN followings ON user_id=followee_id")
					.where("group_id=? AND group_memberships.accepted=1 AND followings.accepted=1 AND mutual=1 AND follower_id=?"+_tentative, groupID, userID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			List<Integer> ids=new SQLQueryBuilder(conn)
					.selectFrom("group_memberships")
					.columns("user_id")
					.join("RIGHT JOIN followings ON user_id=followee_id")
					.where("group_id=? AND group_memberships.accepted=1 AND followings.accepted=1 AND mutual=1 AND follower_id=?"+_tentative, groupID, userID)
					.orderBy(switch(order){
						case ID_ASC -> "user_id ASC";
						case ID_DESC -> "user_id DESC";
						case TIME_ASC -> "time ASC";
						case TIME_DESC -> "time DESC";
						case RANDOM -> throw new IllegalArgumentException();
					})
					.limit(count, offset)
					.executeAndGetIntList();
			return new PaginatedList<>(ids, total, offset, count);
		}
	}

	public static Group.MembershipState getUserMembershipState(int groupID, int userID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			try(ResultSet res=new SQLQueryBuilder(conn).selectFrom("group_memberships").where("group_id=? AND user_id=?", groupID, userID).execute()){
				if(!res.next()){
					return new SQLQueryBuilder(conn).selectFrom("group_invites").count().where("group_id=? AND invitee_id=?", groupID, userID).executeAndGetInt()==0 ? Group.MembershipState.NONE : Group.MembershipState.INVITED;
				}
				if(!res.getBoolean("accepted"))
					return Group.MembershipState.REQUESTED;
				return res.getBoolean("tentative") ? Group.MembershipState.TENTATIVE_MEMBER : Group.MembershipState.MEMBER;
			}
		}
	}

	public static Map<Integer, Group.MembershipState> getUserMembershipStates(Collection<Integer> groupIDs, int userID) throws SQLException{
		if(groupIDs.size()==1){
			int id=groupIDs.iterator().next();
			return Map.of(id, getUserMembershipState(id, userID));
		}
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			HashMap<Integer, Group.MembershipState> states=new HashMap<>();
			for(int id:groupIDs)
				states.put(id, Group.MembershipState.NONE);
			Set<Integer> remainingIDs=new HashSet<>(groupIDs);

			try(ResultSet res=new SQLQueryBuilder(conn)
					.selectFrom("group_memberships")
					.columns("group_id", "accepted", "tentative")
					.whereIn("group_id", groupIDs)
					.andWhere("user_id=?", userID)
					.execute()){
				while(res.next()){
					int id=res.getInt("group_id");
					states.put(id, res.getBoolean("accepted") ? (res.getBoolean("tentative") ? Group.MembershipState.TENTATIVE_MEMBER : Group.MembershipState.MEMBER) : Group.MembershipState.REQUESTED);
					remainingIDs.remove(id);
				}
			}

			if(!remainingIDs.isEmpty()){
				new SQLQueryBuilder(conn)
						.selectFrom("group_invites")
						.columns("group_id")
						.whereIn("group_id", remainingIDs)
						.andWhere("invitee_id=?", userID)
						.executeAndGetIntStream()
						.forEach(id->states.put(id, Group.MembershipState.INVITED));
			}

			return states;
		}
	}

	public static Map<Integer, Group.MembershipState> getMembershipStates(int groupID, Collection<Integer> userIDs, boolean needInvites) throws SQLException{
		if(userIDs.size()==1){
			int id=userIDs.iterator().next();
			return Map.of(id, getUserMembershipState(groupID, id));
		}
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			HashMap<Integer, Group.MembershipState> states=new HashMap<>();
			for(int id:userIDs)
				states.put(id, Group.MembershipState.NONE);
			Set<Integer> remainingIDs=new HashSet<>(userIDs);

			try(ResultSet res=new SQLQueryBuilder(conn)
					.selectFrom("group_memberships")
					.columns("user_id", "accepted", "tentative")
					.whereIn("user_id", userIDs)
					.andWhere("group_id=?", groupID)
					.execute()){
				while(res.next()){
					int id=res.getInt("user_id");
					states.put(id, res.getBoolean("accepted") ? (res.getBoolean("tentative") ? Group.MembershipState.TENTATIVE_MEMBER : Group.MembershipState.MEMBER) : Group.MembershipState.REQUESTED);
					remainingIDs.remove(id);
				}
			}

			if(!remainingIDs.isEmpty() && needInvites){
				new SQLQueryBuilder(conn)
						.selectFrom("group_invites")
						.columns("invitee_id")
						.whereIn("invitee_id", remainingIDs)
						.andWhere("group_id=?", groupID)
						.executeAndGetIntStream()
						.forEach(id->states.put(id, Group.MembershipState.INVITED));
			}

			return states;
		}
	}

	public static void joinGroup(Group group, int userID, boolean tentative, boolean accepted) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			DatabaseUtils.doWithTransaction(conn, ()->{
				new SQLQueryBuilder(conn)
						.insertInto("group_memberships")
						.value("user_id", userID)
						.value("group_id", group.id)
						.value("tentative", tentative)
						.value("accepted", accepted)
						.executeNoResult();

				if(accepted){
					String memberCountField=tentative ? "tentative_member_count" : "member_count";
					new SQLQueryBuilder(conn)
							.update("groups")
							.valueExpr(memberCountField, memberCountField+"+1")
							.where("id=?", group.id)
							.executeNoResult();
				}

				deleteInvitation(userID, group.id, group.isEvent());
				removeFromCache(group);
			});
		}
	}

	public static void updateUserEventDecision(Group group, int userID, boolean tentative) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			DatabaseUtils.doWithTransaction(conn, ()->{
				new SQLQueryBuilder(conn)
						.update("group_memberships")
						.where("user_id=? AND group_id=?", userID, group.id)
						.value("tentative", tentative)
						.executeNoResult();

				String memberCountFieldOld=tentative ? "member_count" : "tentative_member_count";
				String memberCountFieldNew=tentative ? "tentative_member_count" : "member_count";
				new SQLQueryBuilder(conn)
						.update("groups")
						.valueExpr(memberCountFieldOld, memberCountFieldOld+"-1")
						.valueExpr(memberCountFieldNew, memberCountFieldNew+"+1")
						.where("id=?", group.id)
						.executeNoResult();
				removeFromCache(group);
			});
		}
	}

	public static void leaveGroup(Group group, int userID, boolean tentative, boolean wasAccepted) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			DatabaseUtils.doWithTransaction(conn, ()->{
				new SQLQueryBuilder(conn)
						.deleteFrom("group_memberships")
						.where("user_id=? AND group_id=?", userID, group.id)
						.executeNoResult();

				if(wasAccepted){
					String memberCountField=tentative ? "tentative_member_count" : "member_count";
					new SQLQueryBuilder(conn)
							.update("groups")
							.valueExpr(memberCountField, "GREATEST(0, CAST("+memberCountField+" AS SIGNED)-1)")
							.where("id=?", group.id)
							.executeNoResult();
				}

				removeFromCache(group);
			});
		}
	}

	public static PaginatedList<Group> getUserGroups(int userID, int offset, int count, boolean includePrivate) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			String query="SELECT %s FROM group_memberships JOIN `groups` ON group_id=`groups`.id WHERE user_id=? AND accepted=1 AND `groups`.`type`=0";
			if(!includePrivate){
				query+=" AND `groups`.`access_type`<>2";
			}
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, String.format(Locale.US, query, "COUNT(*)"), userID);
			int total=DatabaseUtils.oneFieldToInt(stmt.executeQuery());
			if(total==0)
				return new PaginatedList<>(Collections.emptyList(), 0, 0, count);
			if(includePrivate)
				query+=" ORDER BY hints_rank DESC";
			else
				query+=" ORDER BY group_id ASC";
			query+=" LIMIT ? OFFSET ?";
			stmt=SQLQueryBuilder.prepareStatement(conn, String.format(Locale.US, query, "group_id"), userID, count, offset);
			try(ResultSet res=stmt.executeQuery()){
				return new PaginatedList<>(getByIdAsList(DatabaseUtils.intResultSetToList(res)), total, offset, count);
			}
		}
	}

	public static PaginatedList<Group> getUserEvents(int userID, GroupsController.EventsType type, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			String query="SELECT %s FROM group_memberships JOIN `groups` ON group_id=`groups`.id WHERE user_id=? AND accepted=1 AND `groups`.`type`=1";
			query+=switch(type){
				case PAST -> " AND event_start_time<=CURRENT_TIMESTAMP()";
				case FUTURE -> " AND event_start_time>CURRENT_TIMESTAMP()";
				case ALL -> "";
			};
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, String.format(Locale.US, query, "COUNT(*)"), userID);
			int total=DatabaseUtils.oneFieldToInt(stmt.executeQuery());
			if(total==0)
				return PaginatedList.emptyList(count);
			query+=" ORDER BY event_start_time "+(type==GroupsController.EventsType.PAST ? "DESC" : "ASC")+" LIMIT ? OFFSET ?";
			stmt=SQLQueryBuilder.prepareStatement(conn, String.format(Locale.US, query, "group_id"), userID, count, offset);
			try(ResultSet res=stmt.executeQuery()){
				return new PaginatedList<>(getByIdAsList(DatabaseUtils.intResultSetToList(res)), total, offset, count);
			}
		}
	}

	public static PaginatedList<Group> getUserManagedGroups(int userID, Group.AdminLevel minLevel, boolean events, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("group_admins")
					.count()
					.join("JOIN `groups` ON group_id=`groups`.id")
					.where("user_id=? AND level>? AND `groups`.type=?", userID, minLevel, events ? Group.Type.EVENT : Group.Type.GROUP)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			List<Integer> ids=new SQLQueryBuilder(conn)
					.selectFrom("group_admins")
					.columns("group_id")
					.join("JOIN `groups` ON group_id=`groups`.id")
					.where("user_id=? AND level>? AND `groups`.type=?", userID, minLevel, events ? Group.Type.EVENT : Group.Type.GROUP)
					.executeAndGetIntList();
			return new PaginatedList<>(getByIdAsList(ids), total, offset, count);
		}
	}

	public static Group.AdminLevel getGroupMemberAdminLevel(int groupID, int userID) throws SQLException{
		int level=new SQLQueryBuilder()
				.selectFrom("group_admins")
				.columns("level")
				.where("group_id=? AND user_id=?", groupID, userID)
				.executeAndGetInt();
		return level==-1 ? Group.AdminLevel.REGULAR : Group.AdminLevel.values()[level];
	}

	public static Map<Integer, Group.AdminLevel> getGroupMemberAdminLevels(Collection<Integer> groupIDs, int userID) throws SQLException{
		if(groupIDs.size()==1){
			int id=groupIDs.iterator().next();
			return Map.of(id, getGroupMemberAdminLevel(id, userID));
		}
		HashMap<Integer, Group.AdminLevel> levels=new HashMap<>();
		for(int id:groupIDs)
			levels.put(id, Group.AdminLevel.REGULAR);
		new SQLQueryBuilder()
				.selectFrom("group_admins")
				.columns("group_id", "level")
				.whereIn("group_id", groupIDs)
				.andWhere("user_id=?", userID)
				.executeAsStream(r->new Pair<>(r.getInt(1), r.getInt(2)))
				.forEach(ids->levels.put(ids.first(), Group.AdminLevel.values()[ids.second()]));
		return levels;
	}

	public static void setMemberAccepted(Group group, int userID, boolean accepted) throws SQLException{
		int groupID=group.id;
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int _tentative=new SQLQueryBuilder(conn)
					.selectFrom("group_memberships")
					.columns("tentative")
					.where("group_id=? AND user_id=? AND accepted=?", groupID, userID, !accepted)
					.executeAndGetInt();
			if(_tentative==-1)
				return;
			boolean tentative=_tentative==1;

			new SQLQueryBuilder(conn)
					.update("group_memberships")
					.value("accepted", accepted)
					.where("group_id=? AND user_id=?", groupID, userID)
					.executeNoResult();

			String memberCountField=tentative ? "tentative_member_count" : "member_count";
			new SQLQueryBuilder(conn)
					.update("groups")
					.valueExpr(memberCountField, "GREATEST(0, CAST("+memberCountField+" AS SIGNED)"+(accepted ? "+1" : "-1")+")")
					.where("id=?", groupID)
					.executeNoResult();
			removeFromCache(group);
		}
	}

	@NotNull
	@Unmodifiable
	public static List<GroupAdmin> getGroupAdmins(int groupID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("group_admins")
				.where("group_id=?", groupID)
				.orderBy("display_order ASC")
				.executeAsStream(GroupAdmin::fromResultSet)
				.toList();
	}

	public static GroupAdmin getGroupAdmin(int groupID, int userID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("group_admins")
				.where("group_id=? AND user_id=?", groupID, userID)
				.executeAndGetSingleObject(GroupAdmin::fromResultSet);
	}

	public static List<GroupAdmin> getUserManagedGroupsWithLevels(int userID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("group_admins")
				.where("user_id=?", userID)
				.executeAsStream(GroupAdmin::fromResultSet)
				.toList();
	}

	public static void addOrUpdateGroupAdmin(int groupID, int userID, String title, @Nullable Group.AdminLevel level) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			synchronized(adminUpdateLock){
				GroupAdmin existing=getGroupAdmin(groupID, userID);
				if(existing!=null){
					SQLQueryBuilder b=new SQLQueryBuilder(conn)
							.update("group_admins")
							.where("group_id=? AND user_id=?", groupID, userID)
							.value("title", title);
					if(existing.level!=Group.AdminLevel.OWNER && level!=null){
						b.value("level", level.ordinal());
					}
					b.executeNoResult();
				}else if(level!=null){
					int order=new SQLQueryBuilder(conn)
							.selectFrom("group_admins")
							.selectExpr("MAX(display_order)+1")
							.where("group_id=?", groupID)
							.executeAndGetInt();
					new SQLQueryBuilder(conn)
							.insertInto("group_admins")
							.value("group_id", groupID)
							.value("user_id", userID)
							.value("title", title)
							.value("level", level.ordinal())
							.value("display_order", order)
							.executeNoResult();
				}
				SessionStorage.removeFromUserPermissionsCache(userID);
			}
		}
	}

	public static void removeGroupAdmin(int groupID, int userID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			synchronized(adminUpdateLock){
				int order=new SQLQueryBuilder(conn)
						.selectFrom("group_admins")
						.columns("display_order")
						.where("group_id=? AND user_id=?", groupID, userID)
						.executeAndGetInt();
				if(order==-1)
					return;
				new SQLQueryBuilder(conn)
						.deleteFrom("group_admins")
						.where("group_id=? AND user_id=?", groupID, userID)
						.executeNoResult();
				new SQLQueryBuilder(conn)
						.update("group_admins")
						.valueExpr("display_order", "display_order-1")
						.where("group_id=? AND display_order>?", groupID, order)
						.executeNoResult();
				SessionStorage.removeFromUserPermissionsCache(userID);
			}
		}
	}

	public static void setGroupAdminOrder(int groupID, int userID, int newOrder) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			synchronized(adminUpdateLock){
				int order=new SQLQueryBuilder(conn)
						.selectFrom("group_admins")
						.columns("display_order")
						.where("group_id=? AND user_id=?", groupID, userID)
						.executeAndGetInt();
				if(order==-1 || order==newOrder)
					return;
				int count=new SQLQueryBuilder(conn).selectFrom("group_admins").count().where("group_id=?", groupID).executeAndGetInt();
				if(newOrder>=count)
					return;
				new SQLQueryBuilder(conn)
						.update("group_admins")
						.where("group_id=? AND user_id=?", groupID, userID)
						.value("display_order", newOrder)
						.executeNoResult();
				if(newOrder<order){
					new SQLQueryBuilder(conn)
							.update("group_admins")
							.where("group_id=? AND display_order>=? AND display_order<? AND user_id<>?", groupID, newOrder, order, userID)
							.valueExpr("display_order", "display_order+1")
							.executeNoResult();
				}else{
					new SQLQueryBuilder(conn)
							.update("group_admins")
							.where("group_id=? AND display_order<=? AND display_order>? AND user_id<>?", groupID, newOrder, order, userID)
							.valueExpr("display_order", "display_order-1")
							.executeNoResult();
				}
			}
		}
	}

	public static void updateProfilePicture(Group group, String serializedPic) throws SQLException{
		new SQLQueryBuilder()
				.update("groups")
				.value("avatar", serializedPic)
				.where("id=?", group.id)
				.executeNoResult();
		removeFromCache(group);
	}

	public static void updateGroupGeneralInfo(Group group, String name, String username, String aboutSrc, String about, Instant eventStart, Instant eventEnd, Group.AccessType accessType) throws SQLException{
		new SQLQueryBuilder()
				.update("groups")
				.value("name", name)
				.value("username", username)
				.value("about_source", aboutSrc)
				.value("about", about)
				.value("event_start_time", eventStart)
				.value("event_end_time", eventEnd)
				.value("access_type", accessType)
				.where("id=?", group.id)
				.executeNoResult();

		group.name=name;
		group.username=username;
		new SQLQueryBuilder()
				.update("qsearch_index")
				.value("string", getQSearchStringForGroup(group))
				.where("group_id=?", group.id)
				.executeNoResult();

		removeFromCache(group);
	}

	public static void updateProfileFields(Group group) throws SQLException{
		new SQLQueryBuilder()
				.update("groups")
				.value("profile_fields", group.serializeProfileFields())
				.where("id=?", group.id)
				.executeNoResult();
		removeFromCache(group);
	}

	public static boolean isUserBlocked(int ownerID, int targetID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("blocks_group_user")
				.count()
				.where("owner_id=? AND user_id=?", ownerID, targetID)
				.executeAndGetInt()==1;
	}

	public static Set<Integer> getBlockingGroups(int selfID, Collection<Integer> ids) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("blocks_group_user")
				.columns("owner_id")
				.whereIn("owner_id", ids)
				.andWhere("user_id=?", selfID)
				.executeAndGetIntStream()
				.boxed()
				.collect(Collectors.toSet());
	}

	public static void blockUser(int selfID, int targetID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			new SQLQueryBuilder(conn)
					.insertIgnoreInto("blocks_group_user")
					.value("owner_id", selfID)
					.value("user_id", targetID)
					.executeNoResult();
			new SQLQueryBuilder(conn)
					.deleteFrom("group_memberships")
					.where("user_id=? AND group_id=?", targetID, selfID)
					.executeNoResult();
		}
	}

	public static void unblockUser(int selfID, int targetID) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("blocks_group_user")
				.where("owner_id=? AND user_id=?", selfID, targetID)
				.executeNoResult();
	}

	public static PaginatedList<User> getBlockedUsers(int selfID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("blocks_group_user")
					.count()
					.where("owner_id=?", selfID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);

			List<User> users=UserStorage.getByIdAsList(new SQLQueryBuilder(conn)
					.selectFrom("blocks_group_user")
					.columns("user_id")
					.where("owner_id=?", selfID)
					.limit(count, offset)
					.executeAndGetIntList());
			return new PaginatedList<>(users, total, offset, count);
		}
	}

	public static int getLocalGroupCount() throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("groups")
				.count()
				.executeAndGetInt();
	}

	private static void putIntoCache(Group group){
		cacheByID.put(group.id, group);
		cacheByUsername.put(group.getFullUsername().toLowerCase(), group);
	}

	private static void removeFromCache(Group group){
		cacheByID.remove(group.id);
		cacheByUsername.remove(group.getFullUsername().toLowerCase());
	}

	static String getQSearchStringForGroup(Group group){
		String s=TextProcessor.transliterate(group.name)+" "+group.username;
		return s;
	}

	public static List<Group> getUserEventsInTimeRange(int userID, Instant from, Instant to) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT group_id, event_start_time FROM group_memberships JOIN `groups` ON `groups`.id=group_memberships.group_id WHERE user_id=? AND accepted=1 AND `groups`.type=1 AND event_start_time>=? AND event_start_time<?", userID, from, to);
			return getByIdAsList(DatabaseUtils.intResultSetToList(stmt.executeQuery()));
		}
	}

	public static IntStream getAllMembersAsStream(int groupID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("group_memberships")
				.columns("user_id")
				.where("accepted=1 AND group_id=?", groupID)
				.executeAndGetIntStream();
	}

	public static int putInvitation(int groupID, int inviterID, int inviteeID, boolean isEvent) throws SQLException{
		return new SQLQueryBuilder()
				.insertInto("group_invites")
				.value("group_id", groupID)
				.value("inviter_id", inviterID)
				.value("invitee_id", inviteeID)
				.value("is_event", isEvent)
				.executeAndGetID();
	}

	public static List<GroupInvitation> getUserInvitations(int userID, boolean isEvent, int offset, int count) throws SQLException{
		List<IntPair> ids=new SQLQueryBuilder()
				.selectFrom("group_invites")
				.columns("group_id", "inviter_id")
				.where("invitee_id=? AND is_event=?", userID, isEvent)
				.limit(count, offset)
				.executeAsStream(r->new IntPair(r.getInt(1), r.getInt(2)))
				.toList();
		Set<Integer> needGroups=ids.stream().map(IntPair::first).collect(Collectors.toSet());
		Set<Integer> needUsers=ids.stream().map(IntPair::second).collect(Collectors.toSet());
		Map<Integer, Group> groups=getById(needGroups);
		Map<Integer, User> users=UserStorage.getById(needUsers, false);
		// All groups and users must exist, this is taken care of by schema constraints
		return ids.stream().map(i->new GroupInvitation(groups.get(i.first()), users.get(i.second()))).collect(Collectors.toList());
	}

	public static int deleteInvitation(int userID, int groupID, boolean isEvent) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int id=new SQLQueryBuilder(conn)
					.selectFrom("group_invites")
					.columns("id")
					.where("invitee_id=? AND group_id=?", userID, groupID)
					.executeAndGetInt();
			if(id<1)
				return id;
			int count=new SQLQueryBuilder(conn)
					.deleteFrom("group_invites")
					.where("id=?", id)
					.executeUpdate();
			if(count>0){
				UserNotifications notifications=NotificationsStorage.getNotificationsFromCache(userID);
				if(notifications!=null){
					if(isEvent)
						notifications.incNewEventInvitationsCount(-count);
					else
						notifications.incNewGroupInvitationsCount(-count);
				}
			}
			return id;
		}
	}

	public static PaginatedList<User> getGroupJoinRequests(int groupID, int offset, int count) throws SQLException{
		int total=getJoinRequestCount(groupID);
		if(total==0)
			return PaginatedList.emptyList(count);
		return new PaginatedList<>(UserStorage.getByIdAsList(new SQLQueryBuilder()
				.selectFrom("group_memberships")
				.columns("user_id")
				.where("group_id=? AND accepted=0", groupID)
				.orderBy("time DESC")
				.limit(count, offset)
				.executeAndGetIntList()), total, offset, count);
	}

	public static int getJoinRequestCount(int groupID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("group_memberships")
				.count()
				.where("group_id=? AND accepted=0", groupID)
				.executeAndGetInt();
	}

	public static PaginatedList<User> getGroupInvitations(int groupID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("group_invites")
					.count()
					.where("group_id=?", groupID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			List<Integer> ids=new SQLQueryBuilder(conn)
					.selectFrom("group_invites")
					.columns("invitee_id")
					.where("group_id=?", groupID)
					.orderBy("id DESC")
					.limit(count, offset)
					.executeAndGetIntList();
			return new PaginatedList<>(UserStorage.getByIdAsList(ids), total, offset, count);
		}
	}

	private static URI localUserURI(int id){
		return Config.localURI("/users/"+id);
	}

	private static URI localGroupURI(int id){
		return Config.localURI("/groups/"+id);
	}

	public static void setMemberCount(Group group, int count, boolean tentative) throws SQLException{
		String field=tentative ? "tentative_member_count" : "member_count";
		new SQLQueryBuilder()
				.update("groups")
				.value(field, count)
				.where("id=?", group.id)
				.executeNoResult();
		removeFromCache(group);
	}

	public static int getLocalMembersCount(int groupID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT COUNT(*) FROM `group_memberships` JOIN `users` ON `user_id`=`users`.id WHERE group_id=? AND accepted=1", groupID);
			return DatabaseUtils.oneFieldToInt(stmt.executeQuery());
		}
	}

	public static boolean incrementGroupHintsRank(int userID, int groupID, int amount) throws SQLException{
		return new SQLQueryBuilder()
				.update("group_memberships")
				.valueExpr("hints_rank", "hints_rank+?", amount*1000)
				.where("user_id=? AND group_id=?", userID, groupID)
				.executeUpdate()>0;
	}

	public static void normalizeGroupHintsRanksIfNeeded(Set<Integer> userIDs) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			List<Integer> filteredIDs=new SQLQueryBuilder(conn)
					.selectFrom("group_memberships")
					.columns("user_id")
					.whereIn("user_id", userIDs)
					.groupBy("user_id HAVING MAX(hints_rank)>500000")
					.executeAndGetIntList();
			for(int id:filteredIDs){
				new SQLQueryBuilder(conn)
						.update("group_memberships")
						.where("user_id=?", id)
						.valueExpr("hints_rank", "FLOOR(hints_rank/2)")
						.executeNoResult();
			}
		}
	}

	public static void setGroupBanStatus(Group group, GroupBanStatus banStatus, String banInfo) throws SQLException{
		new SQLQueryBuilder()
				.update("groups")
				.where("id=?", group.id)
				.value("ban_status", banStatus)
				.value("ban_info", banInfo)
				.executeNoResult();
		removeFromCache(group);
	}

	public static void deleteGroup(Group group) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			// Delete media file refs first because triggers don't trigger on cascade deletes. Argh.
			new SQLQueryBuilder(conn)
					.deleteFrom("media_file_refs")
					.where("owner_group_id=?", group.id)
					.executeNoResult();

			new SQLQueryBuilder(conn)
					.deleteFrom("groups")
					.where("id=?", group.id)
					.executeNoResult();
			removeFromCache(group);
		}
	}

	public static List<Group> getTerminallyBannedGroups() throws SQLException{
		return getByIdAsList(new SQLQueryBuilder()
				.selectFrom("groups")
				.columns("id")
				.whereIn("ban_status", GroupBanStatus.SELF_DEACTIVATED, GroupBanStatus.SUSPENDED)
				.executeAndGetIntList());
	}

	public static void updateUsername(Group group, String username) throws SQLException{
		new SQLQueryBuilder()
				.update("groups")
				.where("id=?", group.id)
				.value("username", username)
				.executeNoResult();
		group.username=username;
		new SQLQueryBuilder()
				.update("qsearch_index")
				.value("string", getQSearchStringForGroup(group))
				.where("group_id=?", group.id)
				.executeNoResult();
		removeFromCache(group);
	}

	// region Links

	public static long createLink(int groupID, String url, String title, ObjectLinkResolver.ObjectTypeAndID object, long imageID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int displayOrder=new SQLQueryBuilder(conn)
					.selectFrom("group_links")
					.selectExpr("IFNULL(MAX(display_order), -1)+1")
					.where("group_id=?", groupID)
					.executeAndGetInt();
			return new SQLQueryBuilder(conn)
					.insertInto("group_links")
					.value("group_id", groupID)
					.value("url", url)
					.value("title", title)
					.value("object_type", object==null ? null : object.type().id)
					.value("object_id", object==null ? null : object.id())
					.value("image_id", imageID==0 ? null : imageID)
					.value("display_order", displayOrder)
					.executeAndGetIDLong();
		}
	}

	public static List<GroupLink> getGroupLinks(int groupID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			List<GroupLink> links=new SQLQueryBuilder(conn)
					.selectFrom("group_links")
					.where("group_id=?", groupID)
					.orderBy("display_order ASC")
					.executeAsStream(GroupLink::fromResultSet)
					.toList();
			Set<Long> needFiles=links.stream()
					.map(l->l.image instanceof LocalImage li ? li : null)
					.filter(Objects::nonNull)
					.map(li->li.fileID)
					.collect(Collectors.toSet());
			if(!needFiles.isEmpty()){
				Map<Long, MediaFileRecord> files=MediaStorage.getMediaFileRecords(needFiles);
				for(GroupLink link:links){
					if(link.image instanceof LocalImage li){
						MediaFileRecord mfr=files.get(li.fileID);
						if(mfr!=null)
							li.fillIn(mfr);
					}
				}
			}
			return links;
		}
	}

	public static GroupLink getGroupLink(int groupID, long linkID) throws SQLException{
		GroupLink link=new SQLQueryBuilder()
				.selectFrom("group_links")
				.where("group_id=? AND id=?", groupID, linkID)
				.executeAndGetSingleObject(GroupLink::fromResultSet);
		if(link!=null){
			if(link.image instanceof LocalImage li){
				MediaFileRecord mfr=MediaStorage.getMediaFileRecord(li.fileID);
				if(mfr!=null)
					li.fillIn(mfr);
			}
		}
		return link;
	}

	public static void setLinkOrder(int groupID, long linkID, int newOrder) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
//			synchronized(adminUpdateLock){
				int order=new SQLQueryBuilder(conn)
						.selectFrom("group_links")
						.columns("display_order")
						.where("group_id=? AND id=?", groupID, linkID)
						.executeAndGetInt();
				if(order==-1 || order==newOrder)
					return;
				int count=new SQLQueryBuilder(conn).selectFrom("group_links").count().where("group_id=?", groupID).executeAndGetInt();
				if(newOrder>=count)
					return;
				new SQLQueryBuilder(conn)
						.update("group_links")
						.where("group_id=? AND id=?", groupID, linkID)
						.value("display_order", newOrder)
						.executeNoResult();
				if(newOrder<order){
					new SQLQueryBuilder(conn)
							.update("group_links")
							.where("group_id=? AND display_order>=? AND display_order<? AND id<>?", groupID, newOrder, order, linkID)
							.valueExpr("display_order", "display_order+1")
							.executeNoResult();
				}else{
					new SQLQueryBuilder(conn)
							.update("group_links")
							.where("group_id=? AND display_order<=? AND display_order>? AND id<>?", groupID, newOrder, order, linkID)
							.valueExpr("display_order", "display_order-1")
							.executeNoResult();
				}
//			}
		}
	}

	public static void updateLinkTitle(int groupID, long linkID, String title) throws SQLException{
		new SQLQueryBuilder()
				.update("group_links")
				.where("group_id=? AND id=?", groupID, linkID)
				.value("title", title)
				.executeNoResult();
	}

	public static void deleteLink(int groupID, long linkID) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("group_links")
				.where("group_id=? AND id=?", groupID, linkID)
				.executeNoResult();
	}

	public static void resolveLink(int groupID, long linkID, ObjectLinkResolver.ObjectTypeAndID obj) throws SQLException{
		new SQLQueryBuilder()
				.update("group_links")
				.where("group_id=? AND id=?", groupID, linkID)
				.value("object_type", obj.type().id)
				.value("object_id", obj.id())
				.executeNoResult();
	}

	// endregion
}
