import path from "node:path";
import { BrokerLedger } from "./ledger.js";

let ledger = null;

export function registerBroker(api) {
  api.registerService({
    id: "assistant-capability-broker",
    start: async (ctx) => {
      ledger = new BrokerLedger(path.join(ctx.stateDir, "assistant-capability-broker"));
    },
    stop: async () => {
      ledger?.close();
      ledger = null;
    },
  });
  api.registerGatewayMethod(
    "assistant.broker.status",
    async ({ respond }) => {
      if (!ledger) {
        respond(false, { error: "broker_not_running" });
        return;
      }
      respond(true, ledger.status());
    },
    { scope: "operator.read" },
  );
}

export default {
  id: "assistant-capability-broker",
  name: "Assistant Capability Broker",
  description: "Durable typed plans and receipts for the agentic assistant.",
  configSchema: {
    type: "object",
    properties: {},
    additionalProperties: false,
  },
  register(api) {
    registerBroker(api);
  },
};
