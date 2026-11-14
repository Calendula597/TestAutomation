package io.github.calendula597.testplatform.platformserver.manual;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("test_plan_item")
public class TestPlanItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long planId;

    private Integer orderIndex;

    private Long caseVersionId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public Integer getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(Integer orderIndex) {
        this.orderIndex = orderIndex;
    }

    public Long getCaseVersionId() {
        return caseVersionId;
    }

    public void setCaseVersionId(Long caseVersionId) {
        this.caseVersionId = caseVersionId;
    }
}
