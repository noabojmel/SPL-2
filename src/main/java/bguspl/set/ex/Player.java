package bguspl.set.ex;

import java.util.logging.Level;

import bguspl.set.Env;
import java.util.Random;
import java.util.Vector;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /*the tokens that are currently on the table by this player */
    protected int[][] tokensOnTable={{-1,-1,-1},
                                    {-1,-1,-1}};

    /*the number of tokens that are on the table by this player */
    public int tokensCounter;
    
    /*waiting presses for this player */
    private Vector<Integer> playerPresses;

    private final Dealer dealer;

    private boolean available = true;//if the player is available to place tokens (not sleeping)
    public int shouldSleep = 0;//0-not sleeping, 1-point sleep, 2-panelty sleep

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.tokensCounter=0;
        this.playerPresses=new Vector<Integer>(3);
        this.dealer=dealer;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            while(!playerPresses.isEmpty()&& shouldSleep==0&& !terminate){//execute the oldest press by player
                int currSlot=playerPresses.remove(0);
                aiThread.interrupt();
                tokenAction(currSlot);
            }
            if(shouldSleep==1)//sleeping because of a point
            {
                env.ui.setFreeze(id, env.config.pointFreezeMillis);
                try{Thread.currentThread().sleep(env.config.pointFreezeMillis);}
                    catch(InterruptedException ignored){}
                point();
            }
            if(shouldSleep==2){//sleeping because of a panelty
                penalty();
            }
            shouldSleep=0;
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        Random rand=new Random();
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                if(playerPresses.size()<3){
                    int number= rand.nextInt(12);//stimulate key presses
                    keyPressed(number);
                }
                else{
                    try {
                        synchronized (this) { wait(); }
                    } catch (InterruptedException ignored) {}
                }
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate=true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(table.slotToCard[slot]!=null && available){//legal press and not sleeping
            if(playerPresses.size()<3){
                playerPresses.add(slot);
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        score++;
        env.ui.setScore(id, score);
        env.ui.setFreeze(id, 0);
        synchronized(this){
            for (int i = 0; i < tokensOnTable[0].length; i++) {//restting tokens
                tokensOnTable[0][i]=-1;
                tokensOnTable[1][i]=-1;
            }
            tokensCounter=0;
        }
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        available = true;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        for(long i = env.config.penaltyFreezeMillis/1000; i>0; i--){
            this.env.ui.setFreeze(id, i*1000);
            try{
                playerThread.sleep(900);
            } catch(InterruptedException e){}
        }
        this.env.ui.setFreeze(id, 0);
        available = true;
    }

    public int score() {
        return score;
    }

    //remove or places token according to the player press
    public void tokenAction(int slot){
        boolean tokenExists=false;
        int empty=-1;
        for(int i=0;i<tokensOnTable[1].length &&!tokenExists;i++ ){
            if(tokensOnTable[0][i]==slot){//slot was already pressed so we need to remove it
                tokenExists=true;
                tokensOnTable[0][i]=-1;
                tokensOnTable[1][i]=-1;
                table.removeToken(id, slot);
                tokensCounter--;
                return;
            }
            if(tokensOnTable[0][i]==-1){
                empty=i;
            }
        }
        if(!tokenExists){//new token on table
            if(tokensCounter!=3){//player tried to put 4th token on table - can happen after illegal set
                tokensOnTable[0][empty]=slot;
                tokensOnTable[1][empty]=table.slotToCard[slot];
                tokensCounter++;
                table.placeToken(id, slot);
                if(tokensCounter==3){//annonce a set
                    available = false;
                    dealer.addSet(this);
                    while(!dealer.isFree){
                        try{playerThread.sleep(50);
                        }
                          catch(InterruptedException ignored){}
                    }
                    dealer.dealerThread.interrupt();
                    synchronized(this){
                    try{wait();}catch(InterruptedException ignored){};
                    }
                    available = true;
                }
             }
        }
    }

    public void callSet(){
        dealer.setCalls.add(this);
    }

    public Thread getThread(){
        return playerThread;
    }

    public int getId(){
        return id;
    }

    public void setThread(){
        playerThread=new Thread(this, "" + id);
    }

    public void removeAllTokens(){
        tokensCounter=0;
        for (int i = 0; i < tokensOnTable[0].length; i++) {
            tokensOnTable[0][i]=-1;
            tokensOnTable[1][i]=-1;
        }
    }

    public void setAvailability(boolean av)
    {
        available = av;
    }

    public boolean isHuman(){
        return human;
    }

    public Thread getAiThread(){
        return aiThread;
    }
}