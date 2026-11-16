package moe.qingu.youran.item;

/**
 * 按元数据分好几种的物品（掩模、芯片、主板线的板子、底片、外设的钢壳 / 塑料壳……）：客户端按这里给每个元数据登记模型
 * （{@code client.ClientModels}）。没实现这个接口的物品只登记元数据 0。
 */
public interface MetaModelled {
    /** 要登记模型的元数据 */
    int[] modelMetas();

    /** 这个元数据用的物品模型名（{@code models/item/<名字>.json}，不带模组名） */
    String modelName(int meta);
}
