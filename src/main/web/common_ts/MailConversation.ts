// The chat-style private message view: /my/mail/with/<peer>
class MailConversation{
	public static current:MailConversation=null;

	private root:HTMLElement;
	private messagesInner:HTMLElement;
	private scroller:HTMLElement;
	public peerID:number;
	private loadingEarlier:boolean=false;

	public constructor(root:HTMLElement){
		this.root=root;
		this.peerID=parseInt(root.dataset.peer);
		this.messagesInner=ge("conversationMessagesInner");
		this.scroller=mobile ? null : root.qs(".convMessages");
		MailConversation.current=this;

		var earlier=ge("conversationEarlierLink");
		if(earlier){
			earlier.addEventListener("click", (ev)=>{
				ev.preventDefault();
				ev.stopPropagation();
				this.loadEarlier();
			}, false);
		}

		var formEl=root.qs(".wallPostForm") as HTMLElement;
		if(formEl && formEl.customData && formEl.customData.postFormObj){
			var postForm=formEl.customData.postFormObj as PostForm;
			var form=ge("wallPostFormForm_mailMessage") as HTMLFormElement;
			// Tell the server which message was the last one shown, so it only returns what is new
			form.addEventListener("submit", ()=>this.updateLastIDField(), true);
			postForm.onSendDone=(success:boolean)=>{
				if(success){
					var input=ge("postFormText_mailMessage") as HTMLTextAreaElement;
					input.value="";
					if(input.resizeToFitContent)
						input.resizeToFitContent();
					input.focus();
				}
			};
		}
		var lastIDField=ge("conversationLastID") as HTMLInputElement;
		if(lastIDField)
			lastIDField.value=root.dataset.lastId;

		this.scrollToBottom();
	}

	private updateLastIDField(){
		var lastIDField=ge("conversationLastID") as HTMLInputElement;
		if(lastIDField)
			lastIDField.value=this.root.dataset.lastId;
	}

	public scrollToBottom(){
		if(this.scroller){
			this.scroller.scrollTop=this.scroller.scrollHeight;
		}else{
			window.scrollTo(0, document.body.scrollHeight);
		}
	}

	private loadEarlier(){
		if(this.loadingEarlier)
			return;
		this.loadingEarlier=true;
		var link=ge("conversationEarlierLink");
		var loader=ge("conversationEarlierLoader");
		if(link) link.hide();
		if(loader) loader.show();
		var prevHeight=this.scroller ? this.scroller.scrollHeight : document.body.scrollHeight;
		var prevTop=this.scroller ? this.scroller.scrollTop : window.scrollY;
		var url=`/my/mail/with/${this.peerID}/earlier?before=${this.root.dataset.firstId}&offset=${this.root.dataset.earlierOffset}`;
		ajaxGetAndApplyActions(url, ()=>{
			this.loadingEarlier=false;
			if(link) link.show();
			if(loader) loader.hide();
			this.removeDuplicateDaySeparators();
			// Keep the messages the user was looking at where they were
			var newHeight=this.scroller ? this.scroller.scrollHeight : document.body.scrollHeight;
			if(this.scroller)
				this.scroller.scrollTop=prevTop+(newHeight-prevHeight);
			else
				window.scrollTo(0, prevTop+(newHeight-prevHeight));
		}, ()=>{
			this.loadingEarlier=false;
			if(link) link.show();
			if(loader) loader.hide();
		});
	}

	// Each batch labels its own first day; where a batch starts on the day the next one begins with, drop the repeat
	private removeDuplicateDaySeparators(){
		var prev:HTMLElement=null;
		for(var sep of this.messagesInner.querySelectorAll(".convDaySeparator").unfuck()){
			var el=sep as HTMLElement;
			if(prev && prev.innerText==el.innerText)
				el.remove();
			else
				prev=el;
		}
	}

	public fetchNew(){
		ajaxGetAndApplyActions(`/my/mail/with/${this.peerID}/new?after=${this.root.dataset.lastId}`);
	}

	public static onMessagesAppended(lastID:string){
		var conv=MailConversation.current;
		if(!conv)
			return;
		conv.root.dataset.lastId=lastID;
		conv.updateLastIDField();
		conv.scrollToBottom();
	}

	// Called from the notifier when a new private message arrives
	public static handleIncomingMessage(senderID:number):boolean{
		var conv=MailConversation.current;
		if(conv && conv.peerID==senderID && document.body.contains(conv.root)){
			conv.fetchNew();
			return true;
		}
		return false;
	}

	public static init(root:HTMLElement=null){
		var el=(root || document).querySelector("#conversation") as HTMLElement;
		if(el && !(el.customData && el.customData.conversation)){
			(el.customData || (el.customData={})).conversation=new MailConversation(el);
		}
	}
}
