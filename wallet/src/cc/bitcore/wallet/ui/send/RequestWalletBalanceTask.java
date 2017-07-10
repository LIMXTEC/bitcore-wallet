/*
 * Copyright 2014-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package cc.bitcore.wallet.ui.send;

import java.util.HashSet;
import java.util.Set;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.json.JSONObject;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.squareup.okhttp.CacheControl;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import cc.bitcore.wallet.Constants;
import cc.bitcore.wallet.R;

import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

/**
 * @author Andreas Schildbach
 */
public final class RequestWalletBalanceTask {
    private final Handler backgroundHandler;
    private final Handler callbackHandler;
    private final ResultCallback resultCallback;

    private static final Logger log = LoggerFactory.getLogger(RequestWalletBalanceTask.class);

    public interface ResultCallback {
        void onResult(Set<UTXO> utxos);

        void onFail(int messageResId, Object... messageArgs);
    }

    public RequestWalletBalanceTask(final Handler backgroundHandler, final ResultCallback resultCallback) {
        this.backgroundHandler = backgroundHandler;
        this.callbackHandler = new Handler(Looper.myLooper());
        this.resultCallback = resultCallback;
    }

    public void requestWalletBalance(final AssetManager assets, final Address address) {
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

                final HttpUrl.Builder url = HttpUrl.parse(Constants.CRYPTOID_API_URL).newBuilder();
                url.addQueryParameter("q", "unspent");
                url.addQueryParameter("key", "24455affd924"); //Cryptoid API key
                url.addQueryParameter("active=", address.toBase58());

                log.debug("trying to request wallet balance from {}", url.build());

                final Request.Builder request = new Request.Builder();
                request.url(url.build());
                request.cacheControl(new CacheControl.Builder().noCache().build());
                request.header("Accept-Charset", "utf-8");

                final Call call = Constants.HTTP_CLIENT.newCall(request.build());
                try {
                    final Response response = call.execute();
                    if (response.isSuccessful()) {
                        final String content = response.body().string();
                        final JSONObject json = new JSONObject(content);
                        JSONArray jsonOutputs = json.getJSONArray("unspent_outputs");
                        final Set<UTXO> utxos = new HashSet<>();
                        for (int i = 0; i < jsonOutputs.length(); i++) {
                            final JSONObject jsonOutput = jsonOutputs.getJSONObject(i);
                            final Sha256Hash utxoHash = Sha256Hash.wrap(jsonOutput.getString("tx_hash"));
                            final int utxoIndex = jsonOutput.getInt("tx_ouput_n");
                            final Coin utxoValue = Coin.valueOf(jsonOutput.getLong("value"));
                            final Script script = ScriptBuilder.createOutputScript(address);
                            final UTXO utxo = new UTXO(utxoHash, utxoIndex, utxoValue, 0/* unknown */, false,
                                    script);

                            utxos.add(utxo);
                        }

                        log.info("fetched unspent outputs from {}", url);
                        onResult(utxos);
                        // onResult(transactions.values());

                    } else {
                        final int responseCode = response.code();
                        final String responseMessage = response.message();
                        log.info("got http error '{}: {}' from {}", responseCode, responseMessage, url);
                        onFail(R.string.error_http, responseCode, responseMessage);
                    }
                } catch (final Exception x) {
                    log.info("problem querying unspent outputs from " + url, x);
                    onFail(R.string.error_io, x.getMessage());
                }
            }
        });
    }

    // protected void onResult(final Collection<Transaction> transactions) {
    protected void onResult(final Set<UTXO> utxos) {
        callbackHandler.post(new Runnable() {
            @Override
            public void run() {
                resultCallback.onResult(utxos);
            }
        });
    }

    protected void onFail(final int messageResId, final Object... messageArgs) {
        callbackHandler.post(new Runnable() {
            @Override
            public void run() {
                resultCallback.onFail(messageResId, messageArgs);
            }
        });
    }
}
