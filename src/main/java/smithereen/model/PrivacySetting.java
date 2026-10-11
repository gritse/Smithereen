package smithereen.model;

import com.google.gson.annotations.SerializedName;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import smithereen.Utils;
import smithereen.exceptions.BadRequestException;

public class PrivacySetting{
	public static final PrivacySetting DEFAULT=new PrivacySetting();
	public static final PrivacySetting DEFAULT_FRIENDS_ONLY=new PrivacySetting();

	static{
		DEFAULT_FRIENDS_ONLY.baseRule=Rule.FRIENDS;
	}

	@SerializedName("r")
	public Rule baseRule=Rule.EVERYONE;
	@SerializedName("au")
	public Set<Integer> allowUsers=new HashSet<>();
	@SerializedName("xu")
	public Set<Integer> exceptUsers=new HashSet<>();
	@SerializedName("al")
	public Set<Integer> allowLists=new HashSet<>();
	@SerializedName("xl")
	public Set<Integer> exceptLists=new HashSet<>();
	@SerializedName("alu")
	public Set<Integer> allowListUsers=new HashSet<>();
	@SerializedName("xlu")
	public Set<Integer> exceptListUsers=new HashSet<>();

	public PrivacySetting(){}

	public PrivacySetting(PrivacySetting other){
		this.baseRule=other.baseRule;
		this.allowUsers=new HashSet<>(other.allowUsers);
		this.exceptUsers=new HashSet<>(other.exceptUsers);
		this.allowLists=new HashSet<>(other.allowLists);
		this.exceptLists=new HashSet<>(other.exceptLists);
		this.allowListUsers=new HashSet<>(other.allowListUsers);
		this.exceptListUsers=new HashSet<>(other.exceptListUsers);
	}

	@Override
	public String toString(){
		return "PrivacySetting{"+
				"baseRule="+baseRule+
				", allowUsers="+allowUsers+
				", exceptUsers="+exceptUsers+
				", allowLists="+allowLists+
				", exceptLists="+exceptLists+
				", allowListUsers="+allowListUsers+
				", exceptListUsers="+exceptListUsers+
				'}';
	}

	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof PrivacySetting that)) return false;
		return baseRule==that.baseRule && Objects.equals(allowUsers, that.allowUsers) && Objects.equals(exceptUsers, that.exceptUsers)
				&& Objects.equals(allowLists, that.allowLists) && Objects.equals(exceptLists, that.exceptLists);
	}

	@Override
	public int hashCode(){
		return Objects.hash(baseRule, allowUsers, exceptUsers);
	}

	/**
	 * Get the most-public combination of two privacy settings to send things like a photo album update when its viewing privacy has changed
	 */
	public static PrivacySetting combine(PrivacySetting a, PrivacySetting b){
		if(a.equals(b) && a.allowListUsers.equals(b.allowListUsers) && a.exceptListUsers.equals(b.exceptListUsers))
			return a;
		PrivacySetting c=new PrivacySetting();
		c.baseRule=Rule.values()[Math.min(a.baseRule.ordinal(), b.baseRule.ordinal())];

		// Combine all allowed users together
		c.allowUsers=new HashSet<>(a.allowUsers);
		c.allowUsers.addAll(b.allowUsers);
		c.allowLists=new HashSet<>(a.allowLists);
		c.allowLists.addAll(b.allowLists);
		c.allowListUsers=new HashSet<>(a.allowListUsers);
		c.allowListUsers.addAll(b.allowListUsers);

		// Intersect all excepted users
		c.exceptUsers=a.exceptUsers.stream().filter(b.exceptUsers::contains).collect(Collectors.toSet());
		c.exceptLists=a.exceptLists.stream().filter(b.exceptLists::contains).collect(Collectors.toSet());
		c.exceptListUsers=a.exceptListUsers.stream().filter(b.exceptListUsers::contains).collect(Collectors.toSet());

		return c;
	}

	public Set<Integer> getAllAllowUsers(){
		if(allowListUsers.isEmpty())
			return allowUsers;
		if(allowUsers.isEmpty())
			return allowListUsers;
		return Stream.of(allowUsers, allowListUsers).flatMap(Set::stream).collect(Collectors.toSet());
	}

	public Set<Integer> getAllExceptUsers(){
		if(exceptListUsers.isEmpty())
			return exceptUsers;
		if(exceptUsers.isEmpty())
			return exceptListUsers;
		return Stream.of(exceptUsers, exceptListUsers).flatMap(Set::stream).collect(Collectors.toSet());
	}

	public boolean isFullyPrivate(){
		return baseRule==Rule.NONE && allowUsers.isEmpty();
	}

	public boolean isFullyPublic(){
		return baseRule==Rule.EVERYONE && exceptUsers.isEmpty();
	}

	public static PrivacySetting fromJson(String json){
		PrivacySetting ps;
		try{
			ps=Utils.gson.fromJson(json, PrivacySetting.class);
		}catch(Exception x){
			throw new BadRequestException(x);
		}
		if(ps.baseRule==null)
			throw new BadRequestException();
		if(ps.allowUsers==null)
			ps.allowUsers=Set.of();
		if(ps.exceptUsers==null)
			ps.exceptUsers=Set.of();
		return ps;
	}

	public enum Rule{
		@SerializedName("e")
		EVERYONE,
		@SerializedName("f")
		FRIENDS,
		@SerializedName("ff")
		FRIENDS_OF_FRIENDS,
		@SerializedName("fl")
		FOLLOWERS,
		@SerializedName("fw")
		FOLLOWING,
		@SerializedName("n")
		NONE
	}
}
