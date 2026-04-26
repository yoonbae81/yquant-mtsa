import logging
from typing import Any
from fastapi import FastAPI
from pydantic import BaseModel

from controller import NeoSmartController
from config import API_HOST, API_PORT

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="yquant-mtsa")
controller = NeoSmartController()


class OrderRequest(BaseModel):
    account: str
    symbol: str
    qty: int
    side: str
    price: int | None = None


class OrderResponse(BaseModel):
    success: bool
    order_id: str | None = None
    message: str
    data: dict[str, Any] | None = None


@app.get("/health")
def health_check():
    return {"status": "ok"}


@app.post("/order")
def place_order(req: OrderRequest) -> OrderResponse:
    try:
        controller.start_app()
        return OrderResponse(success=True, message="Order placed")
    except Exception as e:
        logger.exception("Order failed")
        return OrderResponse(success=False, message=str(e))


@app.get("/balance")
def get_balance(account: str) -> dict[str, Any]:
    return {"account": account, "balance": 0}