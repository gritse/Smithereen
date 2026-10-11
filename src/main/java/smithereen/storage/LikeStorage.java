package smithereen.storage;

import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import smithereen.model.LikeObjectType;
import smithereen.Config;
import smithereen.model.LikedObjectID;
import smithereen.model.PaginatedList;
import smithereen.model.User;
import smithereen.model.UserInteractions;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.storage.sql.SQLQueryBuilder;

public class LikeStorage{

	public static int setObjectLiked(int userID, long objectID, LikeObjectType objectType, boolean liked) throws SQLException{
		if(liked)
			return putLike(userID, objectID, objectType);
		else
			return deleteLike(userID, objectID, objectType);
	}

	public static int putLike(int userID, long objectID, LikeObjectType type) throws SQLException{
		return new SQLQueryBuilder()
				.insertIgnoreInto("likes")
				.value("user_id", userID)
				.value("object_id", objectID)
				.value("object_type", type)
				.executeAndGetID();
	}

	private static int deleteLike(int userID, long objectID, LikeObjectType type) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int id=new SQLQueryBuilder(conn)
					.selectFrom("likes")
					.columns("id")
					.where("user_id=? AND object_id=? AND object_type=?", userID, objectID, type.ordinal())
					.executeAndGetInt();
			if(id==-1)
				return -1;
			new SQLQueryBuilder(conn)
					.deleteFrom("likes")
					.where("id=?", id)
					.executeNoResult();
			return id;
		}
	}

	public static void deleteAllLikesForObject(long objectID, LikeObjectType type) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("likes")
				.where("object_id=? AND object_type=?", objectID, type)
				.executeNoResult();
	}

	public static void deleteAllLikesForObjects(Collection<Long> objectIDs, LikeObjectType type) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("likes")
				.whereIn("object_id", objectIDs)
				.andWhere("object_type=?", type)
				.executeNoResult();
	}

	public static PaginatedList<Integer> getLikes(long objectID, LikeObjectType objectType, int selfID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("likes")
					.count()
					.where("object_id=? AND object_type=?", objectID, objectType)
					.executeAndGetInt();

			if(total==0)
				return PaginatedList.emptyList(count);

			List<Integer> userIDs=new SQLQueryBuilder(conn)
					.selectFrom("likes")
					.columns("user_id")
					.where("object_id=? AND object_type=? AND user_id<>?", objectID, objectType, selfID)
					.orderBy("id ASC")
					.limit(count, offset)
					.executeAndGetIntStream()
					.boxed().toList();
			return new PaginatedList<>(userIDs, total, offset, count);
		}
	}

	public static PaginatedList<Integer> getFriendsLikes(long objectID, LikeObjectType objectType, int selfID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("likes")
					.count()
					.join("RIGHT JOIN followings ON user_id=followee_id")
					.where("object_id=? AND object_type=? AND follower_id=? AND mutual=1 AND accepted=1", objectID, objectType, selfID)
					.executeAndGetInt();

			if(total==0)
				return PaginatedList.emptyList(count);

			List<Integer> userIDs=new SQLQueryBuilder(conn)
					.selectFrom("likes")
					.columns("user_id")
					.join("RIGHT JOIN followings ON user_id=followee_id")
					.where("object_id=? AND object_type=? AND follower_id=? AND mutual=1 AND accepted=1", objectID, objectType, selfID)
					.orderBy("likes.id ASC")
					.limit(count, offset)
					.executeAndGetIntStream()
					.boxed().toList();
			return new PaginatedList<>(userIDs, total, offset, count);
		}
	}

	public static int getLikeCount(long objectID, LikeObjectType objectType) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("likes")
				.count()
				.where("object_id=? AND object_type=?", objectID, objectType)
				.executeAndGetInt();
	}

	public static boolean isLiked(long objectID, LikeObjectType objectType, int userID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("likes")
				.count()
				.where("object_id=? AND object_type=? AND user_id=?", objectID, objectType, userID)
				.executeAndGetInt()==1;
	}

	public static PaginatedList<Long> getLikedObjectIDs(int ownerID, LikeObjectType type, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("likes")
					.count()
					.where("user_id=? AND object_type=?", ownerID, type)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			List<Long> ids=new SQLQueryBuilder(conn)
					.selectFrom("likes")
					.columns("object_id")
					.where("user_id=? AND object_type=?", ownerID, type)
					.orderBy("id DESC")
					.limit(count, offset)
					.executeAndGetLongStream()
					.boxed()
					.toList();
			return new PaginatedList<>(ids, total, offset, count);
		}
	}

	public static PaginatedList<LikedObjectID> getLikedObjectIDs(int ownerID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("likes")
					.count()
					.where("user_id=?", ownerID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			List<LikedObjectID> ids=new SQLQueryBuilder(conn)
					.selectFrom("likes")
					.columns("object_type", "object_id")
					.where("user_id=?", ownerID)
					.orderBy("id DESC")
					.limit(count, offset)
					.executeAsStream(res->new LikedObjectID(LikeObjectType.values()[res.getInt("object_type")], res.getLong("object_id")))
					.toList();
			return new PaginatedList<>(ids, total, offset, count);
		}
	}

	public static PaginatedList<Integer> getLikedPostsTopLevelOnly(int ownerID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=DatabaseUtils.oneFieldToInt(SQLQueryBuilder.prepareStatement(conn,
					"SELECT COUNT(*) FROM likes JOIN wall_posts ON likes.object_id=wall_posts.id WHERE likes.user_id=? AND likes.object_type=0 AND wall_posts.reply_key IS NULL", ownerID).executeQuery());
			if(total==0)
				return PaginatedList.emptyList(count);
			List<Integer> ids=DatabaseUtils.intResultSetToList(SQLQueryBuilder.prepareStatement(conn,
					"SELECT likes.object_id FROM likes JOIN wall_posts ON likes.object_id=wall_posts.id WHERE likes.user_id=? AND likes.object_type=0 AND wall_posts.reply_key IS NULL ORDER BY likes.id DESC LIMIT ? OFFSET ?",
					ownerID, count, offset).executeQuery());
			return new PaginatedList<>(ids, total, offset, count);
		}
	}

	public static void fillLikesInInteractions(Map<Long, UserInteractions> interactions, LikeObjectType type, int selfID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			ResultSet res=new SQLQueryBuilder(conn)
					.selectFrom("likes")
					.selectExpr("object_id, count(*)")
					.whereIn("object_id", interactions.keySet())
					.andWhere("object_type=?", type)
					.groupBy("object_id")
					.execute();
			try(res){
				while(res.next()){
					interactions.get(res.getLong(1)).likeCount=res.getInt(2);
				}
			}
			if(selfID!=0){
				new SQLQueryBuilder(conn)
						.selectFrom("likes")
						.columns("object_id")
						.whereIn("object_id", interactions.keySet())
						.andWhere("object_type=? AND user_id=?", type, selfID)
						.executeAndGetLongStream()
						.forEach(id->interactions.get(id).isLiked=true);
			}
		}
	}
}
