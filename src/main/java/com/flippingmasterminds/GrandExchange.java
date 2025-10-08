package com.flippingmasterminds;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

import javax.inject.Inject;
import java.util.*;

public class GrandExchange
{
    private final Client client;
    private final GEDataSender sender;
    private static final String TARGET_URL = "http://api.flippingmasterminds.net/ge"; //"http://127.0.0.1:5000/ge";

    @Inject
    public GrandExchange(Client client, FlippingMastermindsConfig config)
    {
        this.client = client;
        this.sender = new GEDataSender(TARGET_URL, config.apiToken());
    }

    private List<Map<String, Object>> collectOffers()
    {
        List<Map<String, Object>> offers = new ArrayList<>();

        for (int slot = 0; slot < 8; slot++)
        {
            Map<String, Object> data = new HashMap<>();
            GrandExchangeOffer offer = client.getGrandExchangeOffers()[slot];

            data.put("slot", slot);

            if (offer != null && offer.getState() != GrandExchangeOfferState.EMPTY)
            {
                data.put("itemId", offer.getItemId());
                data.put("price", offer.getPrice());
                data.put("totalQuantity", offer.getTotalQuantity());
                data.put("quantitySold", offer.getQuantitySold());
                data.put("state", offer.getState().toString());
            }
            else
            {
                data.put("state", "EMPTY");
                data.put("itemId", -1);
                data.put("quantitySold", 0);
                data.put("totalQuantity", 0);
                data.put("price", 0);
            }

            offers.add(data);
        }

        return offers;
    }

    /**
     * Builds payload with accountHash, playerName, and offers
     */
    private Map<String, Object> buildPayload()
    {
        Map<String, Object> payload = new HashMap<>();

        // If you have account hash & player name from RuneLite, set them here.
        // For now, placeholder values are used.
        payload.put("accountHash", client.getAccountHash()); // requires RuneLite Client API
        payload.put("playerName", client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "UNKNOWN");
        payload.put("offers", collectOffers());

        return payload;
    }

    public void sendAllOffers()
    {
        Map<String, Object> payload = buildPayload();
        sender.send(payload);
    }

    public void shutdown()
    {
        sender.shutdown();
    }
}
