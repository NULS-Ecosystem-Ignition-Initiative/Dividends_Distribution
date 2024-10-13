import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.annotation.Payable;
import io.nuls.contract.sdk.annotation.Required;
import io.nuls.contract.sdk.annotation.View;
import org.checkerframework.checker.units.qual.A;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;


/**
 * @title Nuls Oracles
 *
 *
 * */
public class Stake extends Ownable implements Contract{

    /// Constants
    private static long DURATION = 86400 * 2;           // Rewards distribution period

    /// Variables
    private Address rewardsToken;                       // Reward Token
    private Address stakingToken;                       // Staking Token
    private long    lastUpdateTime;                     // Last time when rewards were updated
    private Address treasury;                           // Treasury Address that will receive Contract Revenue
    private Address rewardDistribution;                 // Address that manages Contract admin functions

    private boolean     locked                = false;              // Prevent Reentrancy Attacks
    private long        periodFinish          = 0;                  // When the stake rewards will end
    private BigInteger  rewardPerTokenStored  = BigInteger.ZERO;    // Current Reward Per Token Stored
    private BigInteger  rewardRate            = BigInteger.ONE;     // Current Distribution Reward Rate
    private BigInteger  _totalSupply          = BigInteger.ZERO;    // Total upply deposited in Contract

    private Map<Address, BigInteger> userRewardPerTokenPaid = new HashMap<Address, BigInteger>(); // User reward per token deposited
    private Map<Address, BigInteger> rewards                = new HashMap<Address, BigInteger>();
    private Map<Address, BigInteger> totalRewards           = new HashMap<Address, BigInteger>();
    private Map<Address, BigInteger> _balances              = new HashMap<Address, BigInteger>();

    /**
     * Constructor
     *
     * @param depositToken Staking token
     * @param rewardTokenAddress Token Reward Address
     * @param treasury  Treasury Address that will receive Contract Revenue
     * */
    public Stake(Address depositToken, String rewardTokenAddress, , Address treasury ) {

        stakingToken            = depositToken;
        rewardsToken            = new Address(rewardTokenAddress);
        this.treasury           = treasury;
        rewardDistribution      = Msg.sender();
        allowWithdrawWithouLock = false;
    }

    protected void nonReentrant(){
        require(!locked, "Already Entered");
        locked = true;
    }

    protected void closeReentrant(){
        require(locked, "Not Entered");
        locked = false;
    }

    /*===========================================

      VIEWS

     ===========================================*/

    /**
     *
     * @return All Nuls Oracle Tokens staked
     */
    @View
    public BigInteger totalSupply() {
        return _totalSupply;
    }

    /**
     *
     * @return All NSWAP earned in staking
     */
    @View
    public BigInteger earned(Address account) {
        return _earned(account);
    }

    /**
     *
     * @return The
     */
    @View
    public BigInteger lockedTime(Address account) {
        return lockTime.get(account);
    }

    @View
    public long lastTimeRewardApplicable() {
        long timestamp = Block.timestamp();
        return timestamp < periodFinish ? timestamp : periodFinish;
    }

    /**
     *
     * @param account
     * @return
     */
    @View
    public BigInteger _balanceOf(Address account) {
        if (_balances.get(account) != null) {
            return _balances.get(account);
        } else {
            return BigInteger.ZERO;
        }
    }

    /*===========================================

      Modifiers

     ===========================================*/

    public void setStakingToken(Address newToken){
        onlyOwner();
        require(newToken != null, "Invalid Address");
        stakingToken = newToken;
    }

    public void setTreasury(Address addr){
        onlyOwner();
        require(addr != null, "Invalid Treasury Address");
        treasury = addr;
    }

    @View
    public Address getStakingToken(){
        return stakingToken;
    }

    private void onlyRewardDistribution() {
        require(Msg.sender().equals(rewardDistribution), "Caller is not reward distribution");
    }

    /*===========================================

      SETTERS

     ===========================================*/

    /**
     *
     * @param _rewardDistribution
     */
    public void setRewardDistribution(Address _rewardDistribution) {
        onlyRewardDistribution();
        rewardDistribution = _rewardDistribution;
    }

    /*===========================================

      NON-ADMIN STATE MODIFIABLE FUNCTIONS

     ===========================================*/

    @Override
    @Payable
    public void _payable() { notifyRewardAmount(Msg.value()); }

    /**
     *
     * @param amount Amount of ORA Tokens to Stake
     */
    public void stake(BigInteger amount) {

        nonReentrant();

        //Update rewards in order to avoid rewards conflits
        updateReward(Msg.sender());

        //Require that the amount is bigger than 0
        require(amount.compareTo(BigInteger.ZERO) > 0, "Cannot stake 0");

        //Get user allowance and check if it is bigger than the amount to stake
        BigInteger allowance = getUserAllowance(stakingToken, Msg.sender(), Msg.address());

        require(allowance.compareTo(amount) >= 0, "Low Allowance");

        //Transfer ORA tokens from user to this contract
        safeTransferFrom(stakingToken, Msg.sender(), Msg.address(), amount);
        //Add the stake amount to the total added
        _totalSupply = _totalSupply.add(amount);
        //Check if the user balance exists and adds the stake amount to the all user staked
        if (_balances.get(Msg.sender()) != null) {
            _balances.put(Msg.sender(), _balances.get(Msg.sender()).add(amount));
        } else {
            _balances.put(Msg.sender(), amount);
        }

        //Emit event with the Stake event
        emit(new Staked(Msg.sender(), amount));

        closeReentrant();
    }

    private BigInteger getReward() {

        nonReentrant();

        updateReward(Msg.sender());

        BigInteger trueReward = rewards.get(Msg.sender());

        if (trueReward.compareTo(BigInteger.ZERO) > 0) {
            rewards.put(Msg.sender(), BigInteger.ZERO);
            Msg.sender().transfer(trueReward);
            emit(new RewardPaid(Msg.sender(), trueReward));
        }

        closeReentrant();

        return trueReward;
    }



    /**
     *
     * @param reward
     */
    @Payable
    public void notifyRewardAmount(BigInteger reward) {

        nonReentrant();

        require(Msg.value().compareTo(reward) >= 0)

        //Update last time disributed rewards
        updateReward(null);

        //
        if (Block.timestamp() >= periodFinish) {

            rewardRate = reward.divide(BigInteger.valueOf(DURATION));

        } else {

            BigInteger remaining = BigInteger.valueOf(periodFinish).subtract(BigInteger.valueOf(Block.timestamp()));
            BigInteger leftover = remaining.multiply(rewardRate);
            rewardRate = reward.add(leftover).divide(BigInteger.valueOf(DURATION));
        }

        lastUpdateTime = Block.timestamp();
        periodFinish = Block.timestamp() + DURATION;

        emit(new RewardAdded(reward));

        closeReentrant();

    }

    /*===========================================

      ADMIN STATE MODIFIABLE FUNCTIONS

     ===========================================*/

    public void recoverNRC20(Address tkn_) {
        //Only rewarder address can give reward
        onlyRewardDistribution();

        require(tkn_ != null, "Token Must be non-zero");

        String[][] argI = new String[][]{new String[]{Msg.address().toString()}};
        BigInteger b = new BigInteger(tkn_.callWithReturnValue("balanceOf", "", argI, BigInteger.ZERO));

        safeTransfer(tkn_, rewardDistribution, b);
    }

    /*===========================================

      PRIVATE FUNCTIONS

     ===========================================*/

    private void updateReward(Address account) {
        rewardPerTokenStored = rewardPerToken();
        lastUpdateTime = lastTimeRewardApplicable();
        if (account != null) {
            rewards.put(account, _earned(account));
            userRewardPerTokenPaid.put(account, rewardPerTokenStored);
        }
    }

    /**
     * Reward per token stored
     * @return
     */
    private BigInteger rewardPerToken() {
        if (_totalSupply.equals(BigInteger.ZERO)) {
            return rewardPerTokenStored;
        }

        return rewardPerTokenStored.
                add(BigInteger.valueOf(lastTimeRewardApplicable()).
                        subtract(BigInteger.valueOf(lastUpdateTime)).
                        multiply(rewardRate).
                        multiply(BigInteger.valueOf((long) 1e8)).
                        divide(_totalSupply));
    }

    private BigInteger _earned(Address account) {
        BigInteger userRewardPer = BigInteger.ZERO;
        if (userRewardPerTokenPaid.get(account) != null) {
            userRewardPer = userRewardPer.add(userRewardPerTokenPaid.get(account));
        }
        BigInteger reward = BigInteger.ZERO;
        if (rewards.get(account) != null) {
            reward = reward.add(rewards.get(account));
        }
        return _balanceOf(account).multiply(rewardPerToken().subtract(userRewardPer)).
                divide(BigInteger.valueOf((long) 1e8)).add(reward);

    }

    private void safeTransfer(@Required Address token, @Required Address recipient, @Required BigInteger amount){
        String[][] argsM = new String[][]{new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transfer", "", argsM, BigInteger.ZERO));
        require(b, "NulswapV1: Failed to transfer");
    }

    private void safeTransferFrom(@Required Address token, @Required Address from, @Required Address recipient, @Required BigInteger amount){
        String[][] args = new String[][]{new String[]{from.toString()}, new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transferFrom", "", args, BigInteger.ZERO));
        require(b, "NulswapV1: Failed to transfer");
    }

    private BigInteger getUserAllowance(@Required Address token, @Required Address owner, @Required Address mover){
        String[][] args = new String[][]{new String[]{owner.toString()}, new String[]{mover.toString()}};
        BigInteger b = new BigInteger(token.callWithReturnValue("allowance", "", args, BigInteger.ZERO));
        return b;
    }

    /*====================================
    *
    * Events
    *
    * ====================================*/


    class Staked implements Event {
        private Address user;
        private BigInteger amount;

        public Staked(Address user, BigInteger amount) {
            this.user = user;
            this.amount = amount;
        }

        public Address getUser() {
            return user;
        }

        public void setUser(Address user) {
            this.user = user;
        }

        public BigInteger getAmount() {
            return amount;
        }

        public void setAmount(BigInteger amount) {
            this.amount = amount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Staked that = (Staked) o;

            if (user != null ? !user.equals(that.user) : that.user != null) return false;
            return amount != null ? amount.equals(that.amount) : that.amount == null;
        }

        @Override
        public int hashCode() {
            int result = user != null ? user.hashCode() : 0;
            result = 31 * result + (amount != null ? amount.hashCode() : 0);
            return result;
        }


        @Override
        public String toString() {
            return "Staked{" +
                    "user=" + user +
                    ", amount=" + amount +
                    '}';
        }
    }


    class Withdrawn implements Event {
        private Address user;
        private BigInteger amount;

        public Withdrawn(Address user, BigInteger amount) {
            this.user = user;
            this.amount = amount;
        }

        public Address getUser() {
            return user;
        }

        public void setUser(Address user) {
            this.user = user;
        }

        public BigInteger getAmount() {
            return amount;
        }

        public void setAmount(BigInteger amount) {
            this.amount = amount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Withdrawn that = (Withdrawn) o;

            if (user != null ? !user.equals(that.user) : that.user != null) return false;
            return amount != null ? amount.equals(that.amount) : that.amount == null;
        }

        @Override
        public int hashCode() {
            int result = user != null ? user.hashCode() : 0;
            result = 31 * result + (amount != null ? amount.hashCode() : 0);
            return result;
        }


        @Override
        public String toString() {
            return "Withdrawn{" +
                    "user=" + user +
                    ", amount=" + amount +
                    '}';
        }
    }

    class RewardPaid implements Event {
        private Address user;
        private BigInteger amount;

        public RewardPaid(Address user, BigInteger amount) {
            this.user = user;
            this.amount = amount;
        }

        public Address getUser() {
            return user;
        }

        public void setUser(Address user) {
            this.user = user;
        }

        public BigInteger getAmount() {
            return amount;
        }

        public void setAmount(BigInteger amount) {
            this.amount = amount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RewardPaid that = (RewardPaid) o;

            if (user != null ? !user.equals(that.user) : that.user != null) return false;
            return amount != null ? amount.equals(that.amount) : that.amount == null;
        }

        @Override
        public int hashCode() {
            int result = user != null ? user.hashCode() : 0;
            result = 31 * result + (amount != null ? amount.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Withdrawn{" +
                    "user=" + user +
                    ", amount=" + amount +
                    '}';
        }
    }


    class RewardAdded implements Event {
        private BigInteger reward;

        public RewardAdded(BigInteger reward) {
            this.reward = reward;
        }

        public BigInteger getReward() {
            return reward;
        }

        public void setReward(BigInteger reward) {
            this.reward = reward;
        }

        @Override
        public String toString() {
            return "RewardAdded{" +
                    "reward=" + reward +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RewardAdded that = (RewardAdded) o;

            return reward != null ? reward.equals(that.reward) : that.reward == null;
        }

        @Override
        public int hashCode() {
            int result = reward != null ? reward.hashCode() : 0;
            return result;
        }
    }



}