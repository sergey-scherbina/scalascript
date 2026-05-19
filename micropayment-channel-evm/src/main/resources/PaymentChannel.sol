// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

interface IERC20 {
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
    function transfer(address to, uint256 amount) external returns (bool);
}

/// @title PaymentChannel
/// @notice Off-chain payment channel for ERC-20 tokens.
///
/// Lifecycle:
///   1. Payer deploys the contract, locking `deposit` tokens.
///   2. Payer and payee exchange signed state updates off-chain.
///   3a. Cooperative close: both parties sign the final cumulative;
///       either party calls cooperativeClose() to release funds instantly.
///   3b. Unilateral close: payee calls submitFinalState() with the latest
///       signed state; after disputeWindow seconds, payee calls finalise().
///   3c. Payer dispute: if payee submits a stale state, payer calls
///       challenge() with a receipt of higher sequence during the window.
///
/// State-signing (off-chain):
///   hash = keccak256(abi.encodePacked(address(this), sequence, cumulative))
///   payerSig = ecSign(payer, "\x19Ethereum Signed Message:\n32" + hash)
///
/// Cooperative-close signing:
///   hash = keccak256(abi.encodePacked("coop", address(this), cumulative))
///   payerSig = ecSign(payer, "\x19Ethereum Signed Message:\n32" + hash)
///   payeeSig = ecSign(payee, "\x19Ethereum Signed Message:\n32" + hash)

contract PaymentChannel {

    address public immutable payer;
    address public immutable payee;
    address public immutable token;
    uint256 public immutable deposit;
    uint256 public immutable expiryTime;       // unix timestamp: channel expires if unused
    uint256 public immutable disputeWindowSecs;

    enum ChannelState { Open, Closing, Closed }
    ChannelState public channelState;

    uint256 public closingSequence;
    uint256 public closingCumulative;
    uint256 public closingTime;

    event Opened(address indexed payer, address indexed payee, uint256 deposit);
    event StateSubmitted(uint256 sequence, uint256 cumulative, address submitter);
    event Challenged(uint256 newSequence, uint256 newCumulative);
    event Finalised(uint256 payeeAmount, uint256 payerRefund);
    event CooperativelyClosed(uint256 cumulative);

    constructor(
        address _payee,
        address _token,
        uint256 _deposit,
        uint256 _expiryTime,
        uint256 _disputeWindowSecs
    ) {
        require(_payee != address(0), "invalid payee");
        require(_deposit > 0, "deposit must be positive");
        payer              = msg.sender;
        payee              = _payee;
        token              = _token;
        deposit            = _deposit;
        expiryTime         = _expiryTime;
        disputeWindowSecs  = _disputeWindowSecs;
        channelState       = ChannelState.Open;
        require(IERC20(_token).transferFrom(msg.sender, address(this), _deposit), "deposit transfer failed");
        emit Opened(msg.sender, _payee, _deposit);
    }

    /// @notice Payee submits the latest payer-signed state to begin unilateral close.
    function submitFinalState(uint256 sequence, uint256 cumulative, bytes calldata sig) external {
        require(msg.sender == payee, "only payee");
        require(channelState == ChannelState.Open, "not open");
        require(cumulative <= deposit, "cumulative exceeds deposit");
        require(_verifyPayerSig(sequence, cumulative, sig), "invalid payer signature");
        channelState       = ChannelState.Closing;
        closingSequence    = sequence;
        closingCumulative  = cumulative;
        closingTime        = block.timestamp;
        emit StateSubmitted(sequence, cumulative, msg.sender);
    }

    /// @notice Payer disputes a stale submission by presenting a higher-sequence receipt.
    function challenge(uint256 sequence, uint256 cumulative, bytes calldata sig) external {
        require(msg.sender == payer, "only payer");
        require(channelState == ChannelState.Closing, "not closing");
        require(block.timestamp < closingTime + disputeWindowSecs, "dispute window closed");
        require(sequence > closingSequence, "not a higher sequence");
        require(_verifyPayerSig(sequence, cumulative, sig), "invalid payer signature");
        closingSequence   = sequence;
        closingCumulative = cumulative;
        emit Challenged(sequence, cumulative);
    }

    /// @notice After the dispute window, release funds per the accepted final state.
    function finalise() external {
        require(channelState == ChannelState.Closing, "not closing");
        require(block.timestamp >= closingTime + disputeWindowSecs, "dispute window active");
        channelState = ChannelState.Closed;
        uint256 toPayee  = closingCumulative;
        uint256 toPayer  = deposit - toPayee;
        if (toPayee > 0) require(IERC20(token).transfer(payee, toPayee));
        if (toPayer > 0) require(IERC20(token).transfer(payer, toPayer));
        emit Finalised(toPayee, toPayer);
    }

    /// @notice Instant close when both parties agree on the final cumulative amount.
    function cooperativeClose(
        uint256 cumulative,
        bytes calldata payerSig,
        bytes calldata payeeSig
    ) external {
        require(channelState == ChannelState.Open, "not open");
        require(cumulative <= deposit, "cumulative exceeds deposit");
        require(_verifyCoopSigs(cumulative, payerSig, payeeSig), "invalid signatures");
        channelState = ChannelState.Closed;
        uint256 toPayee  = cumulative;
        uint256 toPayer  = deposit - toPayee;
        if (toPayee > 0) require(IERC20(token).transfer(payee, toPayee));
        if (toPayer > 0) require(IERC20(token).transfer(payer, toPayer));
        emit CooperativelyClosed(cumulative);
    }

    // ── internal ──────────────────────────────────────────────────────────────

    function _verifyPayerSig(uint256 sequence, uint256 cumulative, bytes memory sig)
        internal view returns (bool)
    {
        bytes32 hash = keccak256(abi.encodePacked(address(this), sequence, cumulative));
        return _recoverSigner(hash, sig) == payer;
    }

    function _verifyCoopSigs(uint256 cumulative, bytes memory payerSig, bytes memory payeeSig)
        internal view returns (bool)
    {
        bytes32 hash = keccak256(abi.encodePacked("coop", address(this), cumulative));
        return _recoverSigner(hash, payerSig) == payer
            && _recoverSigner(hash, payeeSig) == payee;
    }

    function _recoverSigner(bytes32 msgHash, bytes memory sig)
        internal pure returns (address)
    {
        require(sig.length == 65, "sig must be 65 bytes");
        bytes32 prefixed = keccak256(
            abi.encodePacked("\x19Ethereum Signed Message:\n32", msgHash)
        );
        bytes32 r; bytes32 s; uint8 v;
        assembly {
            r := mload(add(sig, 32))
            s := mload(add(sig, 64))
            v := byte(0, mload(add(sig, 96)))
        }
        if (v < 27) v += 27;
        return ecrecover(prefixed, v, r, s);
    }
}
